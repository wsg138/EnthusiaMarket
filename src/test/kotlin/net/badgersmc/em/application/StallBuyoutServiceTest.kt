package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.application.IpLimiter
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StallBuyoutServiceTest {

    private val player = UUID.randomUUID()
    private val stallId = StallId("stall_01")

    private fun unownedStall() = Stall(
        id = stallId, regionId = "stall_01", world = "world",
        state = StallState.UNOWNED, owner = OwnerRef.unowned(),
        ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
    )

    private data class ServiceWithMocks(
        val service: StallBuyoutService,
        val economy: EconomyProvider,
    )

    private fun buildService(
        stall: Stall = unownedStall(),
        claimDecision: LimitResolutionService.ClaimDecision = LimitResolutionService.ClaimDecision.Allowed,
        ownedByPlayer: List<Stall> = emptyList(),
        economyWithdrawOk: Boolean = true,
    ): ServiceWithMocks {
        val stalls = mockk<StallRepository>(relaxUnitFun = true)
        every { stalls.findById(stallId) } returns stall
        every { stalls.all() } returns ownedByPlayer

        val auctions = mockk<net.badgersmc.em.domain.auction.AuctionRepository>(relaxed = true)
        every { auctions.findOpenByStall(stallId) } returns null

        val economy = mockk<EconomyProvider>()
        every { economy.withdraw(any(), any()) } returns economyWithdrawOk

        val limits = mockk<LimitResolutionService>(relaxed = true)
        every { limits.canClaim(any(), any(), any(), any()) } returns claimDecision

        val ownership = mockk<StallOwnershipCounter>(relaxed = true)
        every { ownership.counts(player) } returns StallOwnershipCounter.OwnedCounts(
            total = ownedByPlayer.size,
            byKind = ownedByPlayer.groupingBy { it.kind }.eachCount(),
        )

        val svc = StallBuyoutService(
            stalls = stalls,
            offers = mockk(relaxed = true),
            auctions = auctions,
            economy = economy,
            config = EnthusiaMarketConfig(),
            guildProvider = mockk(relaxed = true),
            regionMembers = mockk(relaxed = true),
            limits = limits,
            ownership = ownership,
            ipLimiter = mockk<IpLimiter>(relaxed = true).also { every { it.tryClaimStall(any(), any()) } returns true },
        )
        return ServiceWithMocks(svc, economy)
    }

    @Test
    fun `buy rejects when player at total cap and never charges`() {
        val (svc, economy) = buildService(
            claimDecision = LimitResolutionService.ClaimDecision.Rejected.TotalCapReached(3),
        )
        val result = svc.buy(stallId, player, 100L, "1.2.3.4")
        assertIs<StallBuyoutService.Result.Rejected>(result)
        assertTrue(result.reason.contains("limit reached", ignoreCase = true))
        verify(exactly = 0) { economy.withdraw(any(), any()) }
    }

    @Test
    fun `buy rejects when player at kind cap and never charges`() {
        val (svc, economy) = buildService(
            claimDecision = LimitResolutionService.ClaimDecision.Rejected.KindCapReached("default", 2),
        )
        val result = svc.buy(stallId, player, 100L, "1.2.3.4")
        assertIs<StallBuyoutService.Result.Rejected>(result)
        assertTrue(result.reason.contains("default", ignoreCase = true))
        verify(exactly = 0) { economy.withdraw(any(), any()) }
    }

    @Test
    fun `buy proceeds when under cap`() {
        val (svc, economy) = buildService(
            claimDecision = LimitResolutionService.ClaimDecision.Allowed,
            economyWithdrawOk = true,
        )
        val result = svc.buy(stallId, player, 100L, "1.2.3.4")
        assertIs<StallBuyoutService.Result.Purchased>(result)
        verify { economy.withdraw(player, 100L) }
    }

    @Test
    fun `guild buy skips limit gate`() {
        val guildProvider = mockk<GuildProvider>(relaxed = true)
        every { guildProvider.guildOf(player) } returns mockk(relaxed = true) { every { id } returns "guild1" }
        every { guildProvider.hasShopPermission(any(), any(), any()) } returns true

        val stalls = mockk<StallRepository>(relaxUnitFun = true)
        every { stalls.findById(stallId) } returns unownedStall()
        every { stalls.all() } returns emptyList()

        val auctions = mockk<net.badgersmc.em.domain.auction.AuctionRepository>(relaxed = true)
        every { auctions.findOpenByStall(stallId) } returns null

        val economy = mockk<EconomyProvider>()
        every { economy.withdraw(any(), any()) } returns true

        val limits = mockk<LimitResolutionService>(relaxed = true)
        every { limits.canClaim(any(), any(), any(), any()) } returns
            LimitResolutionService.ClaimDecision.Rejected.TotalCapReached(0)

        val ownership = mockk<StallOwnershipCounter>(relaxed = true)
        every { ownership.counts(player) } returns StallOwnershipCounter.OwnedCounts(0, emptyMap())

        val svc = StallBuyoutService(
            stalls = stalls, offers = mockk(relaxed = true), auctions = auctions,
            economy = economy, config = EnthusiaMarketConfig(),
            guildProvider = guildProvider, regionMembers = mockk(relaxed = true),
            limits = limits, ownership = ownership,
            ipLimiter = mockk<IpLimiter>(relaxed = true).also { every { it.tryClaimStall(any(), any()) } returns true },
        )

        // claimDecision is TotalCapReached(0), but the guild buy (owner.type == GUILD) skips the
        // SOLO-only limit gate entirely — with the happy-path mocks it proceeds to Purchased.
        val result = svc.buyForGuild(stallId, player, 100L, "1.2.3.4")
        assertIs<StallBuyoutService.Result.Purchased>(result)
    }
}
