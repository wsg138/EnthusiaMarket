package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.MarketAcquisitionBlockedException
import net.badgersmc.em.domain.ports.MarketModerationPolicy
import net.badgersmc.em.domain.ports.MarketMutationGate
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.i18n.LangService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuctionModerationIntegrationTest {
    private val playerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val stallId = StallId("stall_01")

    @Test
    fun `createAuction rejects a moderation-held stall`() {
        val fixture = fixture(mutationGate = LockedGate)
        val result = fixture.service.createAuction(stallId, playerId, 100L, null)

        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue(failure.reason.contains("temporarily unavailable"))
        verify(exactly = 0) { fixture.auctions.create(any()) }
    }

    @Test
    fun `placeBid rejects a restricted bidder before auction work`() {
        val fixture = fixture(moderationPolicy = BlockedPolicy)
        val result = fixture.service.placeBid(
            net.badgersmc.em.domain.auction.AuctionId("auction_01"),
            playerId,
            100L,
            "127.0.0.1",
        )

        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue(failure.reason.contains("restricted"))
        verify(exactly = 0) { fixture.auctions.findById(any()) }
    }

    private fun fixture(
        mutationGate: MarketMutationGate = MarketMutationGate.Open,
        moderationPolicy: MarketModerationPolicy = MarketModerationPolicy.AllowAll,
    ): Fixture {
        val auctions = mockk<AuctionRepository>(relaxed = true)
        val stalls = mockk<StallRepository>(relaxed = true).also {
            every { it.findById(stallId) } returns sampleStall()
        }
        return Fixture(
            AuctionLifecycleService(
                auctions, stalls, mockk<EconomyProvider>(relaxed = true), EnthusiaMarketConfig(),
                mockk(relaxed = true), mockk<SellOfferRepository>(relaxed = true),
                mockk<net.badgersmc.em.domain.shop.ShopRepository>(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                lang = mockk<LangService>(relaxed = true), moderationPolicy = moderationPolicy,
                mutationGate = mutationGate,
            ),
            auctions,
        )
    }

    private fun sampleStall() = Stall(
        id = stallId,
        regionId = stallId.value,
        world = "world",
        state = StallState.OWNED,
        owner = OwnerRef.solo(playerId),
        ownerSince = null,
        winningBid = 0L,
        rentTerms = RentTerms.formula(1.0),
    )

    private data class Fixture(
        val service: AuctionLifecycleService,
        val auctions: AuctionRepository,
    )

    private object LockedGate : MarketMutationGate {
        override fun isStallLocked(stallId: String): Boolean = true
    }

    private object BlockedPolicy : MarketModerationPolicy {
        override fun <T> withAcquisitionPermit(playerId: UUID, action: () -> T): T {
            throw MarketAcquisitionBlockedException("Market acquisitions are restricted")
        }

        override fun canAcquire(playerId: UUID): Boolean = false
    }
}
