package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
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
import kotlin.test.assertEquals

class RentCollectionServiceTest {

    private val playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val stallId = StallId("stall_01")
    private val now = Instant.parse("2026-05-24T10:00:00Z")

    private val ownedStall = Stall(
        id = stallId,
        regionId = "stall_01",
        world = "world",
        state = StallState.OWNED,
        owner = OwnerRef.solo(playerUuid),
        ownerSince = now.minus(Duration.ofDays(5)),
        winningBid = 1000L,
        rentTerms = RentTerms.flat(50L)
    )

    private val graceStall = Stall(
        id = StallId("stall_02"),
        regionId = "stall_02",
        world = "world",
        state = StallState.GRACE,
        owner = OwnerRef.solo(playerUuid),
        ownerSince = now.minus(Duration.ofDays(5)),
        winningBid = 1000L,
        rentTerms = RentTerms.flat(50L)
    )

    private val unownedStall = Stall(
        id = StallId("stall_03"),
        regionId = "stall_03",
        world = "world",
        state = StallState.UNOWNED,
        owner = OwnerRef.unowned(),
        ownerSince = null,
        winningBid = 0L,
        rentTerms = RentTerms.flat(0L)
    )

    private val guildId = "guild-1111-2222-3333"
    private val guildStall = Stall(
        id = StallId("stall_g1"), regionId = "stall_g1", world = "world",
        state = StallState.OWNED, owner = OwnerRef.guild(guildId),
        ownerSince = now.minus(Duration.ofDays(5)), winningBid = 1000L,
        rentTerms = RentTerms.flat(50L)
    )
    private val guildGraceStall = guildStall.copy(
        id = StallId("stall_g2"), regionId = "stall_g2",
        state = StallState.GRACE,
        ownerSince = now.minus(Duration.ofDays(5))
    )

    private fun config(
        mode: String = "flat",
        formulaPct: Double = 0.01,
        flatAmount: Long = 50L,
        collectionInterval: String = "P1D",
        gracePeriod: String = "P3D"
    ): EnthusiaMarketConfig {
        val cfg = EnthusiaMarketConfig()
        cfg.rent.mode = mode
        cfg.rent.formulaPct = formulaPct
        cfg.rent.flatAmount = flatAmount
        cfg.rent.collectionInterval = collectionInterval
        cfg.rent.gracePeriod = gracePeriod
        return cfg
    }

    private data class ServiceWithMocks(
        val service: RentCollectionService,
        val stallRepo: StallRepository,
        val shopRepo: net.badgersmc.em.domain.shop.ShopRepository,
        val economy: EconomyProvider,
        val config: EnthusiaMarketConfig,
        val guildProvider: GuildProvider,
        val auctionRepo: AuctionRepository
    )

    private fun buildService(
        stalls: List<Stall> = listOf(ownedStall),
        economyWithdrawOk: Boolean = true,
        gracePeriod: String = "P3D"
    ): ServiceWithMocks {
        val stallRepo = mockk<StallRepository>(relaxUnitFun = true)
        every { stallRepo.all() } returns stalls

        val economy = mockk<EconomyProvider>()
        every { economy.withdraw(any(), any()) } returns economyWithdrawOk

        val guildProvider = mockk<GuildProvider>(relaxed = true)

        val cfg = config(gracePeriod = gracePeriod)

        val shopRepo = mockk<net.badgersmc.em.domain.shop.ShopRepository>(relaxed = true)
        every { shopRepo.findByStall(any()) } returns emptyList()

        val auctionRepo = mockk<AuctionRepository>(relaxed = true)

        return ServiceWithMocks(
            service = RentCollectionService(stallRepo, shopRepo, economy, guildProvider, cfg, auctionRepo),
            stallRepo = stallRepo,
            shopRepo = shopRepo,
            economy = economy,
            config = cfg,
            guildProvider = guildProvider,
            auctionRepo = auctionRepo
        )
    }

    // --- Emergency auction on grace expiry ---

    private fun shop(id: Long, stallId: String) = net.badgersmc.em.domain.shop.Shop(
        id = id, stallId = stallId, owner = playerUuid,
        signWorld = "world", signX = 0, signY = 64, signZ = 0,
        containerWorld = "world", containerX = 0, containerY = 63, containerZ = 0,
        sellItem = "item", sellAmount = 1, costItem = "item", costAmount = 1,
    )

    @Test
    fun `tick past grace starts emergency auction, does NOT wipe shops`() {
        val svc = buildService(stalls = listOf(graceStall), economyWithdrawOk = false, gracePeriod = "P3D")
        every { svc.shopRepo.findByStall("stall_02") } returns
            listOf(shop(11, "stall_02"), shop(12, "stall_02"))

        val report = svc.service.tick()

        assertEquals(1, report.evictions) // still counted as eviction
        // Shops must NOT be deleted (auction winner inherits them)
        verify(exactly = 0) { svc.shopRepo.delete(any()) }
        // An emergency auction must be created
        verify { svc.auctionRepo.create(any()) }
        // Stall must be EMERGENCY_AUCTIONING, not UNOWNED
        verify { svc.stallRepo.save(match {
            it.state == StallState.EMERGENCY_AUCTIONING
        }) }
    }

    // --- tick: collects rent from OWNED stall successfully ---

    @Test
    fun `tick collects rent from OWNED stall successfully`() {
        val svc = buildService(stalls = listOf(ownedStall), economyWithdrawOk = true)

        val report = svc.service.tick()

        assertEquals(1, report.collected)
        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.economy.withdraw(playerUuid, 50L) }
        verify { svc.stallRepo.save(match { it.state == StallState.OWNED }) }
    }

    // --- tick: insufficient balance marks GRACE (defaulted) on first failure ---

    @Test
    fun `tick insufficient balance marks GRACE on first failure`() {
        val svc = buildService(stalls = listOf(ownedStall), economyWithdrawOk = false)

        val report = svc.service.tick()

        assertEquals(0, report.collected)
        assertEquals(1, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.stallRepo.save(match { it.state == StallState.GRACE }) }
        // Shops must be frozen on GRACE entry
        verify { svc.shopRepo.freezeByStall(ownedStall.id.value, true) }
    }

    // --- tick: GRACE + grace expired starts emergency auction ---

    @Test
    fun `tick GRACE and grace expired starts emergency auction`() {
        // Stall has been in GRACE for 5 days, grace period is 3 days
        val svc = buildService(stalls = listOf(graceStall), economyWithdrawOk = false, gracePeriod = "P3D")

        val report = svc.service.tick()

        assertEquals(0, report.collected)
        assertEquals(0, report.defaults)
        assertEquals(1, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.auctionRepo.create(any()) }
        verify { svc.stallRepo.save(match {
            it.state == StallState.EMERGENCY_AUCTIONING
        }) }
    }

    // --- tick: GRACE + grace not expired does NOT evict ---

    @Test
    fun `tick GRACE and grace not expired does NOT evict`() {
        // Stall has been in GRACE for 5 days, grace period is 7 days (not expired)
        val svc = buildService(stalls = listOf(graceStall), economyWithdrawOk = false, gracePeriod = "P7D")

        val report = svc.service.tick(now)

        assertEquals(0, report.collected)
        assertEquals(0, report.defaults) // already in GRACE, not newly defaulted
        assertEquals(0, report.evictions) // grace not expired
        assertEquals(0, report.errors)

        // Should still try to withdraw
        verify { svc.economy.withdraw(playerUuid, 50L) }
    }

    // --- tick: formula mode calculates correct rent amount ---

    @Test
    fun `tick formula mode calculates correct rent using dailyRent`() {
        val formulaStall = ownedStall.copy(
            rentTerms = RentTerms.formula(1.0), // 1% of winning bid
            winningBid = 10000L
        )
        // RentTerms.dailyRent(10000) with formula(1.0):
        // (10000 * 1.0 / 100.0).toLong() = 100L
        val svc = buildService(stalls = listOf(formulaStall), economyWithdrawOk = true)

        val report = svc.service.tick()

        assertEquals(1, report.collected)
        assertEquals(0, report.errors)
        verify { svc.economy.withdraw(playerUuid, 100L) }
    }

    // --- tick: flat mode calculates correct rent amount ---

    @Test
    fun `tick flat mode calculates correct rent using dailyRent`() {
        val flatStall = ownedStall.copy(
            rentTerms = RentTerms.flat(200L),
            winningBid = 5000L
        )
        // RentTerms.dailyRent(5000) with flat(200) = 200L
        val svc = buildService(stalls = listOf(flatStall), economyWithdrawOk = true)

        val report = svc.service.tick()

        assertEquals(1, report.collected)
        assertEquals(0, report.errors)
        verify { svc.economy.withdraw(playerUuid, 200L) }
    }

    // --- tick: UNOWNED stall is skipped ---

    @Test
    fun `tick UNOWNED stall is skipped`() {
        val svc = buildService(stalls = listOf(unownedStall))

        val report = svc.service.tick()

        assertEquals(0, report.collected)
        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify(exactly = 0) { svc.economy.withdraw(any(), any()) }
        verify(exactly = 0) { svc.stallRepo.save(any()) }
    }

    // --- tick: economy failure on GRACE stall past grace -> evicts ---

    @Test
    fun `tick GRACE stall with successful payment restores to OWNED`() {
        // Stall is in GRACE but owner pays successfully — should be restored to OWNED
        val svc = buildService(stalls = listOf(graceStall), economyWithdrawOk = true, gracePeriod = "P3D")

        val report = svc.service.tick()

        assertEquals(1, report.collected)
        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        // Should save as OWNED with updated ownerSince
        verify { svc.stallRepo.save(match {
            it.state == StallState.OWNED && it.ownerSince != null
        }) }
        // Shops must be unfrozen on GRACE→OWNED recovery
        verify { svc.shopRepo.freezeByStall(graceStall.id.value, false) }
    }

    // --- tick: AUCTIONING stall is skipped ---

    @Test
    fun `tick AUCTIONING stall is skipped`() {
        val auctioningStall = ownedStall.copy(
            state = StallState.AUCTIONING,
            id = StallId("stall_auction")
        )
        val svc = buildService(stalls = listOf(auctioningStall))

        val report = svc.service.tick()

        assertEquals(0, report.collected)
        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify(exactly = 0) { svc.economy.withdraw(any(), any()) }
        verify(exactly = 0) { svc.stallRepo.save(any()) }
    }

    // --- tick: GUILD stall collects rent via guild bank ---

    @Test
    fun `tick collects rent from GUILD stall via guild bank`() {
        // build fresh service with bank withdraw stubbed to true
        val svc = buildService(stalls = listOf(guildStall))
        every { svc.guildProvider.bankWithdraw(guildId, 50L) } returns true

        val report = svc.service.tick(now)

        assertEquals(1, report.collected)
        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.guildProvider.bankWithdraw(guildId, 50L) }
        verify(exactly = 0) { svc.economy.withdraw(any(), any()) }
        // Rent timer must advance one collection interval (P1D) on the saved stall.
        verify {
            svc.stallRepo.save(
                match { it.state == StallState.OWNED && it.nextRentAt == now.plus(Duration.ofDays(1)) }
            )
        }
    }

    // --- tick: GUILD stall with insufficient bank balance marks GRACE ---

    @Test
    fun `tick guild stall with insufficient bank balance marks GRACE`() {
        val svc = buildService(stalls = listOf(guildStall))
        every { svc.guildProvider.bankWithdraw(guildId, 50L) } returns false

        val report = svc.service.tick()

        assertEquals(0, report.collected)
        assertEquals(1, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.guildProvider.bankWithdraw(guildId, 50L) }
        verify(exactly = 0) { svc.economy.withdraw(any(), any()) }
        verify { svc.stallRepo.save(match { it.state == StallState.GRACE }) }
    }

    // --- tick: GUILD stall past grace with insufficient bank starts emergency auction ---

    @Test
    fun `tick guild stall past grace with insufficient bank starts emergency auction`() {
        val svc = buildService(stalls = listOf(guildGraceStall), gracePeriod = "P3D")
        every { svc.guildProvider.bankWithdraw(guildId, 50L) } returns false

        val report = svc.service.tick()

        assertEquals(0, report.collected)
        assertEquals(0, report.defaults)
        assertEquals(1, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.guildProvider.bankWithdraw(guildId, 50L) }
        verify(exactly = 0) { svc.economy.withdraw(any(), any()) }
        verify { svc.auctionRepo.create(any()) }
        verify { svc.stallRepo.save(match {
            it.state == StallState.EMERGENCY_AUCTIONING
        }) }
    }

    // --- tick: skips OWNED stall whose nextRentAt is in the future (C-10) ---

    @Test
    fun `tick skips OWNED stall whose nextRentAt is in the future`() {
        // C-10: voluntary extension / fresh buyout pre-pays by pushing
        // nextRentAt forward. Ticker must NOT re-charge in that window.
        val prepaid = ownedStall.copy(nextRentAt = now.plus(Duration.ofDays(1)))
        val svc = buildService(stalls = listOf(prepaid), economyWithdrawOk = true)

        val report = svc.service.tick(now)

        assertEquals(0, report.collected)
        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify(exactly = 0) { svc.economy.withdraw(any(), any()) }
        verify(exactly = 0) { svc.stallRepo.save(any()) }
    }

    // --- tick: charges OWNED stall whose nextRentAt is due (C-10) ---

    @Test
    fun `tick charges OWNED stall whose nextRentAt is due`() {
        // C-10 mirror: when nextRentAt is in the past the charge must fire.
        val due = ownedStall.copy(nextRentAt = now.minus(Duration.ofMinutes(1)))
        val svc = buildService(stalls = listOf(due), economyWithdrawOk = true)

        val report = svc.service.tick(now)

        assertEquals(1, report.collected)
        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.economy.withdraw(playerUuid, 50L) }
    }
}
