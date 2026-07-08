package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.auction.AuctionState
import net.badgersmc.em.domain.auction.Bid
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.SchematicService
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

/**
 * TDD-270 — schematic capture on first claim (REQ-270, REQ-273, REQ-274).
 *
 * Settlement of an auction with a winner must snapshot the stall geometry
 * BEFORE ownership is persisted, and a capture failure must abort the
 * transition and refund the winner.
 */
class AuctionLifecycleSchematicTest {

    private val seller = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val winner = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val stallId = StallId("stall_01")
    private val auctionId = AuctionId("00000000-0000-0000-0000-000000000010")
    private val now = Instant.parse("2026-05-15T10:00:00Z")

    private val stall = Stall(
        id = stallId,
        regionId = "stall_01",
        world = "world",
        state = StallState.AUCTIONING,
        owner = OwnerRef.solo(seller),
        ownerSince = null,
        winningBid = 0L,
        rentTerms = RentTerms.formula(1.0),
    )

    private val expiredAuctionWithWinner = Auction(
        id = auctionId,
        stallId = stallId,
        state = AuctionState.OPEN,
        startAt = now.minus(Duration.ofHours(48)),
        endAt = now.minus(Duration.ofHours(1)),
        startingBid = 100L,
        highBid = Bid(winner, 150L, now.minus(Duration.ofHours(2))),
        antiSnipeWindow = Duration.ofMinutes(10),
        antiSnipeExtension = Duration.ofMinutes(10),
    )

    private fun config(enabled: Boolean = true): EnthusiaMarketConfig =
        EnthusiaMarketConfig().apply { schematics.enabled = enabled }

    private fun buildService(
        schematics: SchematicService,
        cfg: EnthusiaMarketConfig = config(enabled = true),
    ): Triple<AuctionLifecycleService, StallRepository, EconomyProvider> {
        val auctionRepo = mockk<AuctionRepository>(relaxUnitFun = true)
        every { auctionRepo.findExpired() } returns listOf(expiredAuctionWithWinner)
        every { auctionRepo.findById(auctionId) } returns expiredAuctionWithWinner

        val stallRepo = mockk<StallRepository>(relaxUnitFun = true)
        every { stallRepo.findById(stallId) } returns stall
        every { stallRepo.all() } returns listOf(stall)

        val economy = mockk<EconomyProvider>()
        every { economy.withdraw(any(), any()) } returns true
        every { economy.deposit(any(), any()) } returns true

        val limits = mockk<LimitResolutionService>(relaxed = true)
        every { limits.canClaim(any(), any(), any(), any()) } returns
            LimitResolutionService.ClaimDecision.Allowed

        val sellOffers = mockk<SellOfferRepository>(relaxed = true)
        every { sellOffers.findByStall(any()) } returns null

        val service = AuctionLifecycleService(
            auctionRepo, stallRepo, economy, cfg, limits, sellOffers,
            mockk(relaxed = true), mockk(relaxed = true), mockk<IpLimiter>(relaxed = true), schematics,
            mockk(relaxed = true),
        )
        return Triple(service, stallRepo, economy)
    }

    @Test
    fun `settlement captures schematic before awarding ownership`() {
        val schematics = mockk<SchematicService>()
        every { schematics.capture(any(), any(), any()) } returns SchematicService.Result.Success
        val (service, stallRepo, _) = buildService(schematics)

        service.settleExpired()

        // Captured exactly once, keyed by stall, BEFORE the awarded stall is persisted.
        verifyOrder {
            schematics.capture("stall_01", "world", "stall_01")
            stallRepo.save(match { it.owner == OwnerRef.solo(winner) })
        }
        verify(exactly = 1) { schematics.capture("stall_01", "world", "stall_01") }
    }

    @Test
    fun `capture failure aborts award and refunds the winner`() {
        val schematics = mockk<SchematicService>()
        every { schematics.capture(any(), any(), any()) } returns
            SchematicService.Result.Failure(RuntimeException("WE paste boom"))
        val (service, stallRepo, economy) = buildService(schematics)

        service.settleExpired()

        // Winner refunded, ownership never persisted (REQ-274).
        verify(exactly = 1) { economy.deposit(winner, 150L) }
        verify(exactly = 0) { stallRepo.save(match { it.owner == OwnerRef.solo(winner) }) }
    }

    @Test
    fun `disabled snapshots skip capture entirely`() {
        val schematics = mockk<SchematicService>(relaxed = true)
        val (service, _, _) = buildService(schematics, cfg = config(enabled = false))

        service.settleExpired()

        verify(exactly = 0) { schematics.capture(any(), any(), any()) }
    }
}
