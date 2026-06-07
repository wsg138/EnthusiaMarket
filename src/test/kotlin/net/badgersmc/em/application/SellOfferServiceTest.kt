package net.badgersmc.em.application

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.badgersmc.em.application.SellOfferService.Result
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.offer.SellOffer
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.plugin.PluginManager
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SellOfferServiceTest {

    private val seller = UUID.randomUUID()
    private val buyer = UUID.randomUUID()
    private val taxAccount = UUID.randomUUID()
    private val stallId = StallId("s1")

    private fun ownedStall(owner: UUID = seller) = Stall(
        id = stallId,
        regionId = "s1",
        world = "world",
        state = StallState.OWNED,
        owner = OwnerRef.solo(owner),
        ownerSince = Instant.now(),
        winningBid = 100L,
        rentTerms = RentTerms.formula(1.0),
    )

    private fun config(taxPct: Double = 0.10, taxDestination: String = "system") =
        EnthusiaMarketConfig().apply {
            shop.taxPct = taxPct
            shop.taxDestination = taxDestination
        }

    @BeforeTest fun mockBukkit() {
        mockkStatic(Bukkit::class)
        val server = mockk<Server>(relaxed = true)
        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { Bukkit.getServer() } returns server
        every { server.pluginManager } returns pluginManager
    }

    @AfterTest fun unmockBukkit() = unmockkStatic(Bukkit::class)

    // ===== create =====

    @Test fun `create by owner persists offer and fires event`() {
        val offers = mockk<SellOfferRepository>(relaxed = true)
        val stalls = mockk<StallRepository>(relaxed = true)
        val auctions = mockk<AuctionRepository>(relaxed = true)
        every { stalls.findById(stallId) } returns ownedStall()
        every { auctions.findOpenByStall(stallId) } returns null
        every { offers.findByStall(any()) } returns null

        val svc = SellOfferService(offers, stalls, auctions, mockk(relaxed = true), config(), mockk(relaxed = true), mockk<LimitResolutionService>(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true), mockk<CompensationAlertService>(relaxed = true))
        val r = svc.create(stallId, seller, price = 500L)

        val ok = assertIs<Result.Created>(r)
        assertEquals(500L, ok.offer.price)
        verify { offers.save(match { it.stallId == stallId && it.sellerUuid == seller && it.price == 500L }) }
    }

    @Test fun `create by non-owner is NotAuthorised`() {
        val offers = mockk<SellOfferRepository>()
        val stalls = mockk<StallRepository>()
        val auctions = mockk<AuctionRepository>()
        every { stalls.findById(stallId) } returns ownedStall(owner = UUID.randomUUID())

        val svc = SellOfferService(offers, stalls, auctions, mockk(relaxed = true), config(), mockk(relaxed = true), mockk<LimitResolutionService>(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true), mockk<CompensationAlertService>(relaxed = true))
        val r = svc.create(stallId, seller, price = 500L)

        assertEquals(Result.NotAuthorised, r)
        verify(exactly = 0) { offers.save(any()) }
    }

    @Test fun `create on stall with open auction is rejected with AuctionOpen (REQ-263)`() {
        val offers = mockk<SellOfferRepository>()
        val stalls = mockk<StallRepository>()
        val auctions = mockk<AuctionRepository>(relaxed = true)
        every { stalls.findById(stallId) } returns ownedStall()
        every { auctions.findOpenByStall(stallId) } returns mockk(relaxed = true)
        every { offers.findByStall(any()) } returns null

        val svc = SellOfferService(offers, stalls, auctions, mockk(relaxed = true), config(), mockk(relaxed = true), mockk<LimitResolutionService>(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true), mockk<CompensationAlertService>(relaxed = true))
        val r = svc.create(stallId, seller, price = 500L)

        assertEquals(Result.AuctionOpen, r)
        verify(exactly = 0) { offers.save(any()) }
    }

    @Test fun `create on stall with existing offer is rejected with OfferOpen`() {
        val offers = mockk<SellOfferRepository>(relaxed = true)
        val stalls = mockk<StallRepository>()
        val auctions = mockk<AuctionRepository>()
        every { stalls.findById(stallId) } returns ownedStall()
        every { offers.findByStall(stallId) } returns mockk(relaxed = true)

        val svc = SellOfferService(offers, stalls, auctions, mockk(relaxed = true), config(), mockk(relaxed = true), mockk<LimitResolutionService>(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true), mockk<CompensationAlertService>(relaxed = true))
        val r = svc.create(stallId, seller, price = 500L)

        assertEquals(Result.OfferOpen, r)
        verify(exactly = 0) { offers.save(any()) }
    }

    @Test fun `create on missing stall is NotFound`() {
        val stalls = mockk<StallRepository>()
        every { stalls.findById(stallId) } returns null

        val svc = SellOfferService(
            mockk(relaxed = true), stalls, mockk(relaxed = true),
            mockk(relaxed = true), config(), mockk(relaxed = true), mockk<LimitResolutionService>(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true),
            mockk<CompensationAlertService>(relaxed = true),
 )
        assertEquals(Result.NotFound, svc.create(stallId, seller, 500L))
    }

    @Test fun `create with non-positive price is Rejected`() {
        val svc = SellOfferService(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), config(), mockk(relaxed = true), mockk<LimitResolutionService>(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true),
            mockk<CompensationAlertService>(relaxed = true),
 )
        assertIs<Result.Rejected>(svc.create(stallId, seller, 0L))
        assertIs<Result.Rejected>(svc.create(stallId, seller, -5L))
    }

    // ===== cancel =====

    @Test fun `cancel by owning seller deletes offer`() {
        val offers = mockk<SellOfferRepository>(relaxed = true)
        every { offers.findByStall(stallId) } returns
            SellOffer(stallId, seller, 500L, Instant.now())

        val svc = SellOfferService(
            offers, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), config(), mockk(relaxed = true), mockk<LimitResolutionService>(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true),
            mockk<CompensationAlertService>(relaxed = true),
 )
        val r = svc.cancel(stallId, seller)

        assertIs<Result.Cancelled>(r)
        verify { offers.delete(stallId) }
    }

    @Test fun `cancel by non-owner non-manager is NotAuthorised`() {
        val offers = mockk<SellOfferRepository>()
        val stalls = mockk<StallRepository>()
        every { offers.findByStall(stallId) } returns
            SellOffer(stallId, seller, 500L, Instant.now())
        every { stalls.findById(stallId) } returns ownedStall(owner = seller)

        val intruder = UUID.randomUUID()
        val svc = SellOfferService(
            offers, stalls, mockk(relaxed = true),
            mockk(relaxed = true), config(), mockk(relaxed = true), mockk<LimitResolutionService>(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true),
            mockk<CompensationAlertService>(relaxed = true),
 )
        val r = svc.cancel(stallId, intruder)

        assertEquals(Result.NotAuthorised, r)
        verify(exactly = 0) { offers.delete(any()) }
    }

    @Test fun `cancel on missing offer is NotFound`() {
        val offers = mockk<SellOfferRepository>()
        every { offers.findByStall(stallId) } returns null
        val svc = SellOfferService(
            offers, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), config(), mockk(relaxed = true), mockk<LimitResolutionService>(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true),
            mockk<CompensationAlertService>(relaxed = true),
 )
        assertEquals(Result.NotFound, svc.cancel(stallId, seller))
    }

    // ===== purchase =====

    @Test fun `purchase charges buyer total, pays seller, transfers ownership`() {
        val offers = mockk<SellOfferRepository>(relaxed = true)
        val stalls = mockk<StallRepository>(relaxed = true)
        val economy = mockk<EconomyProvider>()
        val limits = mockk<LimitResolutionService>(relaxed = true)
        val ownership = mockk<StallOwnershipCounter>(relaxed = true)
        every { limits.canClaim(any(), any(), any(), any()) } returns LimitResolutionService.ClaimDecision.Allowed
        every { ownership.counts(any()) } returns StallOwnershipCounter.OwnedCounts(total = 0, byKind = emptyMap())
        every { offers.findByStall(stallId) } returns SellOffer(stallId, seller, 1000L, Instant.now())
        every { stalls.findById(stallId) } returns ownedStall()
        every { economy.withdraw(buyer, 1100L) } returns true
        every { economy.deposit(any(), any()) } returns true

        val svc = SellOfferService(
            offers, stalls, mockk(relaxed = true), economy,
            config(taxPct = 0.10, taxDestination = "system"),
            mockk(relaxed = true),
            limits, ownership,
            mockk<CompensationAlertService>(relaxed = true),
        )
        val r = svc.purchase(stallId, buyer)

        val ok = assertIs<Result.Purchased>(r)
        assertEquals(100L, ok.tax) // 10% of 1000

        // Buyer charged TOTAL (price + tax).
        verify { economy.withdraw(buyer, 1100L) }
        // Seller paid price.
        verify { economy.deposit(seller, 1000L) }
        // System sink — no tax deposit.
        verify(exactly = 0) { economy.deposit(taxAccount, any()) }
        // Ownership transferred.
        verify { stalls.save(match { it.owner == OwnerRef.solo(buyer) }) }
        // Offer closed.
        verify { offers.delete(stallId) }
    }

    @Test fun `purchase routes tax to configured destination UUID`() {
        val offers = mockk<SellOfferRepository>(relaxed = true)
        val stalls = mockk<StallRepository>(relaxed = true)
        val economy = mockk<EconomyProvider>()
        val limits = mockk<LimitResolutionService>(relaxed = true)
        val ownership = mockk<StallOwnershipCounter>(relaxed = true)
        every { limits.canClaim(any(), any(), any(), any()) } returns LimitResolutionService.ClaimDecision.Allowed
        every { ownership.counts(any()) } returns StallOwnershipCounter.OwnedCounts(total = 0, byKind = emptyMap())
        every { offers.findByStall(stallId) } returns SellOffer(stallId, seller, 1000L, Instant.now())
        every { stalls.findById(stallId) } returns ownedStall()
        every { economy.withdraw(buyer, 1100L) } returns true
        every { economy.deposit(any(), any()) } returns true

        val svc = SellOfferService(
            offers, stalls, mockk(relaxed = true), economy,
            config(taxPct = 0.10, taxDestination = taxAccount.toString()),
            mockk(relaxed = true),
            limits, ownership,
            mockk<CompensationAlertService>(relaxed = true),
        )
        svc.purchase(stallId, buyer)

        // Tax routed to the configured account.
        verify { economy.deposit(taxAccount, 100L) }
        verify { economy.deposit(seller, 1000L) }
    }

    @Test fun `purchase with insufficient buyer balance Rejected, nothing moves`() {
            val offers = mockk<SellOfferRepository>(relaxed = true)
            val stalls = mockk<StallRepository>(relaxed = true)
            val economy = mockk<EconomyProvider>()
            val limits = mockk<LimitResolutionService>(relaxed = true)
            val ownership = mockk<StallOwnershipCounter>(relaxed = true)
            every { limits.canClaim(any(), any(), any(), any()) } returns LimitResolutionService.ClaimDecision.Allowed
            every { ownership.counts(any()) } returns StallOwnershipCounter.OwnedCounts(total = 0, byKind = emptyMap())
            every { offers.findByStall(stallId) } returns SellOffer(stallId, seller, 1000L, Instant.now())
            every { stalls.findById(stallId) } returns ownedStall()
            every { economy.withdraw(buyer, 1100L) } returns false

            val svc = SellOfferService(
                offers, stalls, mockk(relaxed = true), economy,
                config(taxPct = 0.10), mockk(relaxed = true),
                limits, ownership,
            mockk<CompensationAlertService>(relaxed = true),
            )
        val r = svc.purchase(stallId, buyer)

        assertIs<Result.Rejected>(r)
        verify(exactly = 0) { economy.deposit(any(), any()) }
        verify(exactly = 0) { stalls.save(any()) }
        verify(exactly = 0) { offers.delete(any()) }
    }

    @Test fun `purchase of a guild-owned stall pays the guild bank, not the seller`() {
        val offers = mockk<SellOfferRepository>(relaxed = true)
        val stalls = mockk<StallRepository>(relaxed = true)
        val economy = mockk<EconomyProvider>()
        val guildProvider = mockk<GuildProvider>()
        val limits = mockk<LimitResolutionService>(relaxed = true)
        val ownership = mockk<StallOwnershipCounter>(relaxed = true)
        val guildId = "g1"
        val memberSeller = seller // a guild member listed the offer
        every { limits.canClaim(any(), any(), any(), any()) } returns LimitResolutionService.ClaimDecision.Allowed
        every { ownership.counts(any()) } returns StallOwnershipCounter.OwnedCounts(total = 0, byKind = emptyMap())
        every { offers.findByStall(stallId) } returns SellOffer(stallId, memberSeller, 1000L, Instant.now())
        every { stalls.findById(stallId) } returns Stall(
            id = stallId,
            regionId = "s1",
            world = "world",
            state = StallState.OWNED,
            owner = OwnerRef.guild(guildId),
            ownerSince = Instant.now(),
            winningBid = 100L,
            rentTerms = RentTerms.formula(1.0),
        )
        every { economy.withdraw(buyer, 1100L) } returns true
        every { economy.deposit(any(), any()) } returns true
        every { guildProvider.bankDeposit(guildId, 1000L) } returns true

        val svc = SellOfferService(
            offers, stalls, mockk(relaxed = true), economy,
            config(taxPct = 0.10, taxDestination = "system"),
            guildProvider,
            limits, ownership,
            mockk<CompensationAlertService>(relaxed = true),
        )
        val r = svc.purchase(stallId, buyer)

        val ok = assertIs<Result.Purchased>(r)
        assertEquals(100L, ok.tax) // 10% of 1000

        // Buyer charged TOTAL (price + tax).
        verify { economy.withdraw(buyer, 1100L) }
        // Proceeds go to the guild bank, not the seller's personal balance.
        verify(exactly = 1) { guildProvider.bankDeposit(guildId, 1000L) }
        verify(exactly = 0) { economy.deposit(memberSeller, 1000L) }
        // System sink — no tax deposit.
        verify(exactly = 0) { economy.deposit(taxAccount, any()) }
        // Buyer owns the stall personally now, even though it was guild-owned before.
        verify { stalls.save(match { it.owner == OwnerRef.solo(buyer) }) }
        // Offer closed.
        verify { offers.delete(stallId) }
    }

    @Test fun `purchase by the seller themselves is Rejected`() {
        val offers = mockk<SellOfferRepository>()
        val stalls = mockk<StallRepository>()
        val economy = mockk<EconomyProvider>()
        every { offers.findByStall(stallId) } returns SellOffer(stallId, seller, 1000L, Instant.now())
        every { stalls.findById(stallId) } returns ownedStall()

        val svc = SellOfferService(
            offers, stalls, mockk(relaxed = true), economy,
            config(), mockk(relaxed = true), mockk<LimitResolutionService>(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true),
            mockk<CompensationAlertService>(relaxed = true),
 )
        val r = svc.purchase(stallId, seller)

        assertIs<Result.Rejected>(r)
        confirmVerified(economy)
    }

    @Test fun `purchase on missing offer is NotFound`() {
        val offers = mockk<SellOfferRepository>()
        every { offers.findByStall(stallId) } returns null

        val svc = SellOfferService(
            offers, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), config(), mockk(relaxed = true), mockk<LimitResolutionService>(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true),
            mockk<CompensationAlertService>(relaxed = true),
 )
        assertEquals(Result.NotFound, svc.purchase(stallId, buyer))
    }

    // ===== Compensation alert wiring (C-4) =====

    @Test fun `purchase with failed seller deposit fires alert`() {
        val offers = mockk<SellOfferRepository>(relaxed = true)
        val stalls = mockk<StallRepository>(relaxed = true)
        val economy = mockk<EconomyProvider>()
        val limits = mockk<LimitResolutionService>(relaxed = true)
        val ownership = mockk<StallOwnershipCounter>(relaxed = true)
        val alerter = mockk<CompensationAlertService>(relaxed = true)
        every { limits.canClaim(any(), any(), any(), any()) } returns LimitResolutionService.ClaimDecision.Allowed
        every { ownership.counts(any()) } returns StallOwnershipCounter.OwnedCounts(total = 0, byKind = emptyMap())
        every { offers.findByStall(stallId) } returns SellOffer(stallId, seller, 1000L, Instant.now())
        every { stalls.findById(stallId) } returns ownedStall()
        every { economy.withdraw(buyer, 1100L) } returns true
        every { economy.deposit(any(), any()) } returns false  // fails

        val svc = SellOfferService(
            offers, stalls, mockk(relaxed = true), economy,
            config(taxPct = 0.10, taxDestination = "system"),
            mockk(relaxed = true),
            limits, ownership,
            alerter,
        )
        svc.purchase(stallId, buyer)

        verify {
            alerter.alert(
                context = match { it.contains("sell-offer") },
                detail = any(),
                affected = seller,
                amount = 1000L,
            )
        }
    }

    @Test fun `purchase with failed guild deposit fires alert`() {
        val offers = mockk<SellOfferRepository>(relaxed = true)
        val stalls = mockk<StallRepository>(relaxed = true)
        val economy = mockk<EconomyProvider>()
        val guildProvider = mockk<GuildProvider>()
        val limits = mockk<LimitResolutionService>(relaxed = true)
        val ownership = mockk<StallOwnershipCounter>(relaxed = true)
        val alerter = mockk<CompensationAlertService>(relaxed = true)
        val guildId = "g1"
        every { limits.canClaim(any(), any(), any(), any()) } returns LimitResolutionService.ClaimDecision.Allowed
        every { ownership.counts(any()) } returns StallOwnershipCounter.OwnedCounts(total = 0, byKind = emptyMap())
        every { offers.findByStall(stallId) } returns SellOffer(stallId, seller, 1000L, Instant.now())
        every { stalls.findById(stallId) } returns Stall(
            id = stallId,
            regionId = "s1",
            world = "world",
            state = StallState.OWNED,
            owner = OwnerRef.guild(guildId),
            ownerSince = Instant.now(),
            winningBid = 100L,
            rentTerms = RentTerms.formula(1.0),
        )
        every { economy.withdraw(buyer, 1100L) } returns true
        every { economy.deposit(any(), any()) } returns true
        every { guildProvider.bankDeposit(guildId, 1000L) } returns false  // fails

        val svc = SellOfferService(
            offers, stalls, mockk(relaxed = true), economy,
            config(taxPct = 0.10, taxDestination = "system"),
            guildProvider,
            limits, ownership,
            alerter,
        )
        svc.purchase(stallId, buyer)

        verify {
            alerter.alert(
                context = match { it.contains("sell-offer") },
                detail = any(),
                affected = null,
                amount = 1000L,
            )
        }
    }
}
