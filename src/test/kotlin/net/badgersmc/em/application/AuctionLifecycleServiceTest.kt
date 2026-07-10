package net.badgersmc.em.application

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.auction.AuctionState
import net.badgersmc.em.domain.auction.Bid
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.application.IpLimiter
import net.badgersmc.em.domain.stall.StallState
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuctionLifecycleServiceTest {

    private val playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val otherPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val stallId = StallId("stall_01")
    private val auctionId = AuctionId("00000000-0000-0000-0000-000000000010")
    private val now = Instant.parse("2026-05-15T10:00:00Z")
    private val later = now.plus(Duration.ofHours(24))

    private val sampleStall = Stall(
        id = stallId,
        regionId = "stall_01",
        world = "world",
        state = StallState.AUCTIONING,
        owner = OwnerRef.solo(playerUuid),
        ownerSince = null,
        winningBid = 0L,
        rentTerms = RentTerms.formula(1.0)
    )

    private val sampleAuction = Auction(
        id = auctionId,
        stallId = stallId,
        state = AuctionState.OPEN,
        startAt = now,
        endAt = later,
        startingBid = 100L,
        highBid = null,
        antiSnipeWindow = Duration.ofMinutes(10),
        antiSnipeExtension = Duration.ofMinutes(10)
    )

    private fun config(
        defaultDuration: String = "PT24H",
        minDuration: String = "PT15M",
        maxDuration: String = "P7D",
        antiSnipeSec: Int = 30,
        antiSnipeExtendSec: Int = 30,
        feePct: Double = 0.05,
        minStartingBid: Long = 1
    ): EnthusiaMarketConfig {
        val cfg = EnthusiaMarketConfig()
        cfg.auction.defaultDuration = defaultDuration
        cfg.auction.minDuration = minDuration
        cfg.auction.maxDuration = maxDuration
        cfg.auction.antiSnipeSec = antiSnipeSec
        cfg.auction.antiSnipeExtendSec = antiSnipeExtendSec
        cfg.auction.feePct = feePct
        cfg.auction.minStartingBid = minStartingBid
        return cfg
    }

    private data class ServiceWithMocks(
        val service: AuctionLifecycleService,
        val auctionRepo: AuctionRepository,
        val stallRepo: StallRepository,
        val economy: EconomyProvider,
        val config: EnthusiaMarketConfig,
        val limits: LimitResolutionService,
    )

    private fun buildService(
        stall: Stall = sampleStall,
        auction: Auction? = sampleAuction,
        openAuctionByStall: Auction? = null,
        openAuctions: List<Auction> = emptyList(),
        expiredAuctions: List<Auction> = emptyList(),
        auctionsByStall: List<Auction> = emptyList(),
        economyWithdrawOk: Boolean = true,
        economyDepositOk: Boolean = true,
        ownedByWinner: List<Stall> = emptyList(),
        claimDecision: LimitResolutionService.ClaimDecision = LimitResolutionService.ClaimDecision.Allowed,
        overriddenConfig: EnthusiaMarketConfig? = null,
    ): ServiceWithMocks {
        val auctionRepo = mockk<AuctionRepository>(relaxUnitFun = true)
        every { auctionRepo.findById(auctionId) } returns auction
        every { auctionRepo.findOpenByStall(stallId) } returns openAuctionByStall
        every { auctionRepo.allOpen() } returns openAuctions
        every { auctionRepo.findExpired() } returns expiredAuctions
        every { auctionRepo.findByStall(stallId) } returns auctionsByStall

        val stallRepo = mockk<StallRepository>(relaxUnitFun = true)
        every { stallRepo.findById(stallId) } returns stall
        every { stallRepo.all() } returns ownedByWinner + listOf(stall)

        val economy = mockk<EconomyProvider>()
        every { economy.withdraw(any(), any()) } returns economyWithdrawOk
        every { economy.deposit(any(), any()) } returns economyDepositOk

        val cfg = overriddenConfig ?: config()

        val limits = mockk<LimitResolutionService>(relaxed = true)
        every { limits.canClaim(any(), any(), any(), any()) } returns claimDecision

        // Default: no open sell offer on the stall. Explicit stub because
        // mockk's value-class handling can return non-null defaults for
        // relaxed mocks on inline-class-keyed lookups (StallId is @JvmInline).
        val sellOffers = mockk<SellOfferRepository>(relaxed = true)
        every { sellOffers.findByStall(any()) } returns null

        return ServiceWithMocks(
            service = AuctionLifecycleService(auctionRepo, stallRepo, economy, cfg, limits, sellOffers, mockk(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true), mockk<IpLimiter>(relaxed = true).also { every { it.tryBindAuction(any(), any()) } returns true }, mockk(relaxed = true), mockk(relaxed = true)),
            auctionRepo = auctionRepo,
            stallRepo = stallRepo,
            economy = economy,
            config = cfg,
            limits = limits,
        )
    }

    // ===== createAuction =====

    @Test
    fun `createAuction succeeds with default duration`() {
        val stall = sampleStall
        val svc = buildService(stall = stall, openAuctionByStall = null, auction = null)

        val result = svc.service.createAuction(stallId, playerUuid, 100L, null)

        val success = assertIs<AuctionResult.Success>(result)
        assertEquals(stallId, success.auction.stallId)
        assertEquals(AuctionState.OPEN, success.auction.state)
        assertEquals(100L, success.auction.startingBid)
        val actualDuration = Duration.between(success.auction.startAt, success.auction.endAt)
        assertTrue(actualDuration >= Duration.ofHours(24))
        verify { svc.auctionRepo.create(success.auction) }
    }

    @Test
    fun `createAuction returns Failure when stall not found`() {
        val stallRepo = mockk<StallRepository>()
        every { stallRepo.findById(stallId) } returns null

        val auctionRepo = mockk<AuctionRepository>()
        val economy = mockk<EconomyProvider>()
        val cfg = config()
val svc = AuctionLifecycleService(auctionRepo, stallRepo, economy, cfg, mockk<LimitResolutionService>(relaxed = true).also { every { it.canClaim(any(), any(), any(), any()) } returns LimitResolutionService.ClaimDecision.Allowed }, mockk<SellOfferRepository>(relaxed = true).also { every { it.findByStall(any()) } returns null }, mockk(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true), mockk<IpLimiter>(relaxed = true).also { every { it.tryBindAuction(any(), any()) } returns true }, mockk(relaxed = true), mockk(relaxed = true))

        val result = svc.createAuction(stallId, playerUuid, 100L, null)

        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue { failure.reason.contains("not found", ignoreCase = true) }
    }

    @Test
    fun `createAuction returns Failure when owner mismatch`() {
        val stall = sampleStall.copy(owner = OwnerRef.solo(otherPlayer))
        val svc = buildService(stall = stall, openAuctionByStall = null, auction = null)

        val result = svc.service.createAuction(stallId, playerUuid, 100L, null)

        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue { failure.reason.contains("owner", ignoreCase = true) }
    }

    @Test
    fun `createAuction returns Failure when rival open auction exists`() {
        val existing = sampleAuction
        val svc = buildService(openAuctionByStall = existing, auction = null)

        val result = svc.service.createAuction(stallId, playerUuid, 100L, null)

        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue { failure.reason.contains("open auction", ignoreCase = true) }
    }

    /** REQ-263 — auction creation also refuses when a sell offer is open. */
    @Test
    fun `createAuction returns Failure when an open sell offer exists`() {
        val auctionRepo = mockk<AuctionRepository>(relaxUnitFun = true)
        every { auctionRepo.findOpenByStall(stallId) } returns null

        val stallRepo = mockk<StallRepository>()
        every { stallRepo.findById(stallId) } returns sampleStall

        val sellOffers = mockk<SellOfferRepository>()
        every { sellOffers.findByStall(stallId) } returns
            net.badgersmc.em.domain.offer.SellOffer(
                stallId = stallId,
                sellerUuid = playerUuid,
                price = 500L,
                createdAt = now,
            )

        val svc = AuctionLifecycleService(
            auctionRepo, stallRepo, mockk(relaxed = true), config(),
            mockk<LimitResolutionService>(relaxed = true).also { every { it.canClaim(any(), any(), any(), any()) } returns LimitResolutionService.ClaimDecision.Allowed },
            sellOffers,
            mockk(relaxed = true),
            mockk<StallOwnershipCounter>(relaxed = true),
            mockk<IpLimiter>(relaxed = true).also { every { it.tryBindAuction(any(), any()) } returns true },
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

        val result = svc.createAuction(stallId, playerUuid, 100L, null)
        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue { failure.reason.contains("sell offer", ignoreCase = true) }

        verify(exactly = 0) { auctionRepo.create(any()) }
    }

    @Test
    fun `createAuction returns Failure when starting bid below minimum`() {
        val cfg = config(minStartingBid = 200L)
        val auctionRepo = mockk<AuctionRepository>()
        every { auctionRepo.findOpenByStall(stallId) } returns null
        val stallRepo = mockk<StallRepository>()
        every { stallRepo.findById(stallId) } returns sampleStall
        val economy = mockk<EconomyProvider>()
val svc = AuctionLifecycleService(auctionRepo, stallRepo, economy, cfg, mockk<LimitResolutionService>(relaxed = true).also { every { it.canClaim(any(), any(), any(), any()) } returns LimitResolutionService.ClaimDecision.Allowed }, mockk<SellOfferRepository>(relaxed = true).also { every { it.findByStall(any()) } returns null }, mockk(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true), mockk<IpLimiter>(relaxed = true).also { every { it.tryBindAuction(any(), any()) } returns true }, mockk(relaxed = true), mockk(relaxed = true))

        val result = svc.createAuction(stallId, playerUuid, 100L, null)

        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue { failure.reason.contains("at least", ignoreCase = true) }
    }

    @Test
    fun `createAuction returns Failure when duration too short`() {
        val svc = buildService(openAuctionByStall = null, auction = null)

        val result = svc.service.createAuction(stallId, playerUuid, 100L, "PT1M")

        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue { failure.reason.contains("duration", ignoreCase = true) }
    }

    @Test
    fun `createAuction returns Failure when duration too long`() {
        val svc = buildService(openAuctionByStall = null, auction = null)

        val result = svc.service.createAuction(stallId, playerUuid, 100L, "P30D")

        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue { failure.reason.contains("duration", ignoreCase = true) }
    }

    // ===== placeBid =====

    @Test
    fun `placeBid succeeds with anti-snipe extension`() {
        val openingBid = sampleAuction.copy(highBid = null)
        val svc = buildService(auction = openingBid)

        val result = svc.service.placeBid(auctionId, otherPlayer, 200L, "1.2.3.4")

        val success = assertIs<AuctionResult.Success>(result)
        assertEquals(200L, success.auction.highBid?.amount)
        assertEquals(otherPlayer, success.auction.highBid?.bidder)
        verify { svc.economy.withdraw(otherPlayer, 200L) }
        verify { svc.auctionRepo.save(success.auction) }
    }

    @Test
    fun `placeBid accepts stall id and withdraws upfront`() {
        val svc = buildService(auction = null, openAuctionByStall = sampleAuction)
        every { svc.auctionRepo.findById(AuctionId(stallId.value)) } returns null

        val result = svc.service.placeBid(AuctionId(stallId.value), otherPlayer, 200L, "1.2.3.4")

        val success = assertIs<AuctionResult.Success>(result)
        assertEquals(200L, success.auction.highBid?.amount)
        verify { svc.economy.withdraw(otherPlayer, 200L) }
        verify { svc.auctionRepo.save(success.auction) }
    }

    @Test
    fun `placeBid returns Failure when auction not found`() {
        val auctionRepo = mockk<AuctionRepository>()
        every { auctionRepo.findById(auctionId) } returns null
        every { auctionRepo.findOpenByStall(any()) } returns null
        val stallRepo = mockk<StallRepository>()
        val economy = mockk<EconomyProvider>()
        val cfg = config()
val svc = AuctionLifecycleService(auctionRepo, stallRepo, economy, cfg, mockk<LimitResolutionService>(relaxed = true).also { every { it.canClaim(any(), any(), any(), any()) } returns LimitResolutionService.ClaimDecision.Allowed }, mockk<SellOfferRepository>(relaxed = true).also { every { it.findByStall(any()) } returns null }, mockk(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true), mockk<IpLimiter>(relaxed = true).also { every { it.tryBindAuction(any(), any()) } returns true }, mockk(relaxed = true), mockk(relaxed = true))

        val result = svc.placeBid(auctionId, otherPlayer, 200L, "1.2.3.4")

        assertIs<AuctionResult.NotFound>(result)
    }

    @Test
    fun `placeBid on closed auction returns Failure`() {
        val closed = sampleAuction.copy(state = AuctionState.CLOSED)
        val svc = buildService(auction = closed)

        val result = svc.service.placeBid(auctionId, otherPlayer, 200L, "1.2.3.4")

        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue { failure.reason.contains("not open", ignoreCase = true) }
    }

    @Test
    fun `placeBid with insufficient amount returns Failure`() {
        val svc = buildService(auction = sampleAuction)

        val result = svc.service.placeBid(auctionId, otherPlayer, 50L, "1.2.3.4")

        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue { failure.reason.contains("starting bid", ignoreCase = true) }
        verify(exactly = 0) { svc.economy.withdraw(any(), any()) }
        verify(exactly = 0) { svc.auctionRepo.save(any()) }
    }

    @Test
    fun `placeBid self-outbid withdraws only the difference`() {
        val existing = sampleAuction.copy(highBid = Bid(otherPlayer, 150L, now))
        val svc = buildService(auction = existing)

        val result = svc.service.placeBid(auctionId, otherPlayer, 225L, "1.2.3.4")

        val success = assertIs<AuctionResult.Success>(result)
        assertEquals(225L, success.auction.highBid?.amount)
        verify { svc.economy.withdraw(otherPlayer, 75L) }
        verify(exactly = 0) { svc.economy.withdraw(otherPlayer, 225L) }
        verify(exactly = 0) { svc.economy.deposit(otherPlayer, 150L) }
        verify { svc.auctionRepo.save(success.auction) }
    }

    @Test
    fun `placeBid refunds previous high bidder on outbid`() {
        val existing = sampleAuction.copy(highBid = Bid(playerUuid, 150L, now))
        val svc = buildService(auction = existing)

        val result = svc.service.placeBid(auctionId, otherPlayer, 225L, "1.2.3.4")

        val success = assertIs<AuctionResult.Success>(result)
        assertEquals(otherPlayer, success.auction.highBid?.bidder)
        verify { svc.economy.withdraw(otherPlayer, 225L) }
        verify { svc.auctionRepo.save(success.auction) }
        verify { svc.economy.deposit(playerUuid, 150L) }
    }

    @Test
    fun `placeBid keeps paid new high bidder when previous refund fails`() {
        val existing = sampleAuction.copy(highBid = Bid(playerUuid, 150L, now))
        val svc = buildService(auction = existing)
        every { svc.economy.deposit(playerUuid, 150L) } returns false

        val result = svc.service.placeBid(auctionId, otherPlayer, 225L, "1.2.3.4")

        val success = assertIs<AuctionResult.Success>(result)
        assertEquals(otherPlayer, success.auction.highBid?.bidder)
        verify { svc.economy.withdraw(otherPlayer, 225L) }
        verify { svc.auctionRepo.save(success.auction) }
        verify { svc.economy.deposit(playerUuid, 150L) }
        verify(exactly = 0) { svc.economy.deposit(otherPlayer, 225L) }
    }

    // ===== cancelAuction =====

    @Test
    fun `cancelAuction succeeds for stall owner`() {
        val svc = buildService()

        val result = svc.service.cancelAuction(auctionId, playerUuid)

        val success = assertIs<AuctionResult.Success>(result)
        assertEquals(AuctionState.CLOSED, success.auction.state)
        verify { svc.auctionRepo.save(success.auction) }
    }

    @Test
    fun `cancelAuction refunds active high bid`() {
        val auctionWithBid = sampleAuction.copy(highBid = Bid(otherPlayer, 250L, now))
        val svc = buildService(auction = auctionWithBid)

        val result = svc.service.cancelAuction(auctionId, playerUuid)

        val success = assertIs<AuctionResult.Success>(result)
        assertEquals(AuctionState.CLOSED, success.auction.state)
        verify { svc.auctionRepo.save(success.auction) }
        verify { svc.economy.deposit(otherPlayer, 250L) }
    }

    @Test
    fun `cancelAllAuctions refunds active high bids`() {
        val auctionWithBid = sampleAuction.copy(highBid = Bid(otherPlayer, 250L, now))
        val svc = buildService(openAuctions = listOf(auctionWithBid))

        val count = svc.service.cancelAllAuctions()

        assertEquals(1, count)
        verify { svc.auctionRepo.save(match { it.state == AuctionState.CANCELLED }) }
        verify { svc.economy.deposit(otherPlayer, 250L) }
    }

    @Test
    fun `cancelAuction returns Failure for non-owner`() {
        val svc = buildService()

        val result = svc.service.cancelAuction(auctionId, otherPlayer)

        val failure = assertIs<AuctionResult.Failure>(result)
        assertTrue { failure.reason.contains("owner", ignoreCase = true) }
    }

    @Test
    fun `cancelAuction returns NotFound`() {
        val auctionRepo = mockk<AuctionRepository>()
        every { auctionRepo.findById(auctionId) } returns null
        val stallRepo = mockk<StallRepository>()
        val economy = mockk<EconomyProvider>()
        val cfg = config()
val svc = AuctionLifecycleService(auctionRepo, stallRepo, economy, cfg, mockk<LimitResolutionService>(relaxed = true).also { every { it.canClaim(any(), any(), any(), any()) } returns LimitResolutionService.ClaimDecision.Allowed }, mockk<SellOfferRepository>(relaxed = true).also { every { it.findByStall(any()) } returns null }, mockk(relaxed = true), mockk<StallOwnershipCounter>(relaxed = true), mockk<IpLimiter>(relaxed = true).also { every { it.tryBindAuction(any(), any()) } returns true }, mockk(relaxed = true), mockk(relaxed = true))

        val result = svc.cancelAuction(auctionId, playerUuid)

        assertIs<AuctionResult.NotFound>(result)
    }

    // ===== settleExpired =====

    @Test
    fun `settleExpired awards stall to winner and pays seller`() {
        val winner = otherPlayer
        val bidAmount = 500L
        val feePct = 0.05
        val sellerProceeds = bidAmount - (bidAmount * feePct).toLong() // 475
        val expiredAuction = sampleAuction.copy(
            highBid = Bid(winner, bidAmount, now.plus(Duration.ofHours(23)))
        )
        val svc = buildService(
            expiredAuctions = listOf(expiredAuction),
            economyWithdrawOk = true,
            economyDepositOk = true
        )

        val report = svc.service.settleExpired()

        assertEquals(1, report.settled)
        assertEquals(0, report.errors)

        // Stall awarded to winner
        verify { svc.stallRepo.save(match { stall ->
            stall.owner == OwnerRef.solo(winner) && stall.state == StallState.OWNED
        }) }
        // Seller paid proceeds minus fee
        verify { svc.economy.deposit(playerUuid, sellerProceeds) }
        // Auction saved as closed
        verify { svc.auctionRepo.save(match { auction ->
            auction.state == AuctionState.CLOSED
        }) }
    }

    @Test
    fun `settleExpired with no bids just closes auction`() {
        val expiredAuction = sampleAuction.copy(highBid = null)
        val svc = buildService(expiredAuctions = listOf(expiredAuction))

        val report = svc.service.settleExpired()

        assertEquals(1, report.settled)
        assertEquals(0, report.errors)
        // No stall save — just close auction
        verify { svc.auctionRepo.save(match { it.state == AuctionState.CLOSED }) }
    }

    @Test
    fun `settleExpired handles economy deposit failure gracefully`() {
        val winner = otherPlayer
        val bidAmount = 500L
        val expiredAuction = sampleAuction.copy(
            highBid = Bid(winner, bidAmount, now.plus(Duration.ofHours(23)))
        )
        val svc = buildService(
            expiredAuctions = listOf(expiredAuction),
            economyWithdrawOk = true,
            economyDepositOk = false // deposit fails
        )

        val report = svc.service.settleExpired()

        // Deposit failure is non-fatal: state is already persisted, seller funds held for manual resolution
        assertEquals(1, report.settled)
        assertEquals(0, report.errors)
        // Winner was already charged when the bid was placed; settlement does not charge again.
        verify(exactly = 0) { svc.economy.withdraw(winner, bidAmount) }
        // Stall was still awarded
        verify { svc.stallRepo.save(match { stall ->
            stall.owner == OwnerRef.solo(winner) && stall.state == StallState.OWNED
        }) }
        // Auction was still closed
        verify { svc.auctionRepo.save(match { auction ->
            auction.state == AuctionState.CLOSED
        }) }
    }

    // ===== REQ-212 — limit enforcement on stall claim =====

    @Test
    fun `settleExpired rejects winner over their total cap, closes auction, and refunds held bid`() {
        val winner = otherPlayer
        val winningBid = Bid(amount = 500L, bidder = winner, placedAt = now)
        val expired = sampleAuction.copy(
            state = AuctionState.OPEN,
            highBid = winningBid,
            endAt = now.minusSeconds(60)
        )
        val systemAuctionedStall = sampleStall.copy(
            state = StallState.AUCTIONING,
            owner = OwnerRef.unowned(),
        )

        val svc = buildService(
            stall = systemAuctionedStall,
            expiredAuctions = listOf(expired),
            claimDecision = LimitResolutionService.ClaimDecision.Rejected.TotalCapReached(3),
        )

        svc.service.settleExpired()

        // Limit was consulted before any economy or persistence calls.
        verify { svc.limits.canClaim(winner, any(), any(), any()) }
        // Winner was NOT charged again at settlement and their held bid was refunded.
        verify(exactly = 0) { svc.economy.withdraw(winner, any()) }
        verify { svc.economy.deposit(winner, winningBid.amount) }
        // Seller was NOT paid.
        verify(exactly = 0) { svc.economy.deposit(playerUuid, any()) }
        // Stall ownership NOT mutated to the winner — it reverts to UNOWNED
        // (system-mass-auction with no real owner).
        verify(exactly = 0) {
            svc.stallRepo.save(match { it.owner == OwnerRef.solo(winner) })
        }
        verify {
            svc.stallRepo.save(match {
                it.state == StallState.UNOWNED && it.owner.type == net.badgersmc.em.domain.stall.OwnerType.NONE
            })
        }
        // Auction was still closed so it stops appearing in findExpired().
        verify {
            svc.auctionRepo.save(match { it.state == AuctionState.CLOSED })
        }
    }

    // ===== Pay-upfront bidding: settlement must not charge the winner again =====

    @Test
    fun `settleExpired with held bid awards system stall without charging again`() {
        val winner = otherPlayer
        val expired = sampleAuction.copy(
            highBid = Bid(winner, 500L, now),
            endAt = now.minusSeconds(60),
        )
        val systemStall = sampleStall.copy(
            state = StallState.AUCTIONING,
            owner = OwnerRef.unowned(),
        )
        val svc = buildService(
            stall = systemStall,
            expiredAuctions = listOf(expired),
            economyWithdrawOk = false, // winner can't pay
        )

        val report = svc.service.settleExpired()

        assertEquals(0, report.errors)
        assertEquals(1, report.settled)
        verify(exactly = 0) { svc.economy.withdraw(winner, 500L) }
        verify {
            svc.stallRepo.save(match {
                it.state == StallState.OWNED && it.owner == OwnerRef.solo(winner)
            })
        }
        verify { svc.auctionRepo.save(match { it.state == AuctionState.CLOSED }) }
    }

    @Test
    fun `settleExpired closes auction and refunds held bid when award save throws`() {
        // CodeRabbit on #57: if the stall revert ran BEFORE the auction close
        // and threw, the auction stayed OPEN-and-expired — re-settling every
        // scheduler tick, the exact wedge M-2 fixes. Close must happen first;
        // a failed revert is best-effort.
        val winner = otherPlayer
        val expired = sampleAuction.copy(
            highBid = Bid(winner, 500L, now),
            endAt = now.minusSeconds(60),
        )
        val systemStall = sampleStall.copy(
            state = StallState.AUCTIONING,
            owner = OwnerRef.unowned(),
        )
        val svc = buildService(
            stall = systemStall,
            expiredAuctions = listOf(expired),
            economyWithdrawOk = false,
        )
        every { svc.stallRepo.save(any()) } throws RuntimeException("db down")

        val report = svc.service.settleExpired()

        assertEquals(1, report.errors)
        assertEquals(0, report.settled)
        verify { svc.auctionRepo.save(match { it.state == AuctionState.CLOSED }) }
        verify { svc.economy.deposit(winner, 500L) }
    }

    @Test
    fun `settleExpired with held bid on owner-created auction awards without charging again`() {
        val winner = otherPlayer
        val expired = sampleAuction.copy(
            highBid = Bid(winner, 500L, now),
            endAt = now.minusSeconds(60),
        )
        val ownedStall = sampleStall.copy(state = StallState.OWNED) // SOLO owner keeps the stall
        val svc = buildService(
            stall = ownedStall,
            expiredAuctions = listOf(expired),
            economyWithdrawOk = false,
        )

        val report = svc.service.settleExpired()

        assertEquals(0, report.errors)
        assertEquals(1, report.settled)
        verify(exactly = 0) { svc.economy.withdraw(winner, 500L) }
        verify {
            svc.stallRepo.save(match { it.owner == OwnerRef.solo(winner) && it.state == StallState.OWNED })
        }
        verify { svc.auctionRepo.save(match { it.state == AuctionState.CLOSED }) }
    }

    @Test
    fun `settleExpired proceeds with normal settlement when limit decision is Allowed`() {
        // Counterpart to the rejection case — sanity-checks that the limit
        // gate doesn't accidentally block normal settlements when the
        // decision is Allowed (default in buildService).
        val winner = otherPlayer
        val winningBid = Bid(amount = 500L, bidder = winner, placedAt = now)
        val expired = sampleAuction.copy(
            state = AuctionState.OPEN,
            highBid = winningBid,
            endAt = now.minusSeconds(60)
        )

        val svc = buildService(
            stall = sampleStall,
            expiredAuctions = listOf(expired),
        )

        svc.service.settleExpired()

        verify { svc.limits.canClaim(winner, any(), any(), any()) }
        verify(exactly = 0) { svc.economy.withdraw(winner, 500L) }
        verify {
            svc.stallRepo.save(match { it.owner == OwnerRef.solo(winner) && it.state == StallState.OWNED })
        }
    }

    // ===== extendAuction =====

    @Test
    fun `extendAuction succeeds with valid duration`() {
        val svc = buildService(auction = sampleAuction)
        svc.service.clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)

        val result = svc.service.extendAuction(auctionId, "PT6H")

        assertIs<AuctionResult.Success>(result)
        val extended = (result as AuctionResult.Success).auction
        assertEquals(now.plus(Duration.ofHours(30)), extended.endAt) // 24h + 6h
        verify { svc.auctionRepo.save(any()) }
    }

    @Test
    fun `extendAuction returns NotFound for missing auction`() {
        val svc = buildService(auction = null)
        every { svc.auctionRepo.findOpenByStall(StallId(auctionId.value)) } returns null
        assertIs<AuctionResult.NotFound>(svc.service.extendAuction(auctionId, "PT6H"))
    }

    @Test
    fun `extendAuction fails for non-OPEN auction`() {
        val closed = sampleAuction.copy(state = AuctionState.CLOSED)
        val svc = buildService(auction = closed)

        val result = svc.service.extendAuction(auctionId, "PT6H")
        assertIs<AuctionResult.Failure>(result)
        assertEquals("Only open auctions can be extended", (result as AuctionResult.Failure).reason)
    }

    @Test
    fun `extendAuction fails for invalid duration format`() {
        val svc = buildService(auction = sampleAuction)

        val result = svc.service.extendAuction(auctionId, "banana")
        assertIs<AuctionResult.Failure>(result)
        assertTrue((result as AuctionResult.Failure).reason.contains("Invalid duration"))
    }

    @Test
    fun `extendAuction fails for zero or negative duration`() {
        val svc = buildService(auction = sampleAuction)

        val result = svc.service.extendAuction(auctionId, "PT0S")
        assertIs<AuctionResult.Failure>(result)
        assertEquals("Extension must be a positive duration", (result as AuctionResult.Failure).reason)
    }

    @Test
    fun `extendAuction fails when extension exceeds max duration`() {
        val svc = buildService(auction = sampleAuction, overriddenConfig = config(maxDuration = "PT30H"))
        svc.service.clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)

        // End is now + 24h, max from now is 30h, extension of 20h → 44h > 30h max
        val result = svc.service.extendAuction(auctionId, "PT20H")
        assertIs<AuctionResult.Failure>(result)
        assertTrue((result as AuctionResult.Failure).reason.contains("exceed maximum"))
    }

    // ===== clearStaleBidData =====

    @Test
    fun `clearStaleBidData clears high bid from cancelled auction`() {
        val cancelled = sampleAuction.copy(
            state = AuctionState.CANCELLED,
            highBid = Bid(otherPlayer, 500L, now)
        )
        val svc = buildService(auctionsByStall = listOf(cancelled))

        val cleared = svc.service.clearStaleBidData(stallId)
        assertEquals(1, cleared)
        verify {
            svc.auctionRepo.save(match { it.highBid == null && it.state == AuctionState.CANCELLED })
        }
    }

    @Test
    fun `clearStaleBidData skips OPEN auctions`() {
        val open = sampleAuction.copy(highBid = Bid(otherPlayer, 500L, now))
        val svc = buildService(auctionsByStall = listOf(open))

        val cleared = svc.service.clearStaleBidData(stallId)
        assertEquals(0, cleared)
        verify(exactly = 0) { svc.auctionRepo.save(any()) }
    }

    @Test
    fun `clearStaleBidData skips auctions without high bid`() {
        val closedNoBid = sampleAuction.copy(state = AuctionState.CLOSED, highBid = null)
        val svc = buildService(auctionsByStall = listOf(closedNoBid))

        val cleared = svc.service.clearStaleBidData(stallId)
        assertEquals(0, cleared)
        verify(exactly = 0) { svc.auctionRepo.save(any()) }
    }

    @Test
    fun `clearStaleBidData clears multiple stale auctions`() {
        val bid = Bid(otherPlayer, 500L, now)
        val cancelled = sampleAuction.copy(state = AuctionState.CANCELLED, highBid = bid)
        val closed = sampleAuction.copy(
            id = AuctionId("00000000-0000-0000-0000-000000000020"),
            state = AuctionState.CLOSED,
            highBid = bid
        )
        val open = sampleAuction.copy(highBid = bid) // should be skipped
        val svc = buildService(auctionsByStall = listOf(cancelled, closed, open))

        val cleared = svc.service.clearStaleBidData(stallId)
        assertEquals(2, cleared)
    }
}
