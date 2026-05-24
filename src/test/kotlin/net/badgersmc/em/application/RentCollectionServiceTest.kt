package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.EconomyProvider
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
        val economy: EconomyProvider,
        val config: EnthusiaMarketConfig
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

        val cfg = config(gracePeriod = gracePeriod)

        return ServiceWithMocks(
            service = RentCollectionService(stallRepo, economy, cfg),
            stallRepo = stallRepo,
            economy = economy,
            config = cfg
        )
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
    }

    // --- tick: GRACE + grace expired evicts stall ---

    @Test
    fun `tick GRACE and grace expired evicts stall`() {
        // Stall has been in GRACE for 5 days, grace period is 3 days
        val svc = buildService(stalls = listOf(graceStall), economyWithdrawOk = false, gracePeriod = "P3D")

        val report = svc.service.tick()

        assertEquals(0, report.collected)
        assertEquals(0, report.defaults)
        assertEquals(1, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.stallRepo.save(match {
            it.state == StallState.UNOWNED && it.owner == OwnerRef.unowned()
        }) }
    }

    // --- tick: GRACE + grace not expired does NOT evict ---

    @Test
    fun `tick GRACE and grace not expired does NOT evict`() {
        // Stall has been in GRACE for 5 days, grace period is 7 days (not expired)
        val svc = buildService(stalls = listOf(graceStall), economyWithdrawOk = false, gracePeriod = "P7D")

        val report = svc.service.tick()

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

    // --- tick: GUILD owner is skipped ---

    @Test
    fun `tick GUILD owner stall is skipped`() {
        val guildStall = ownedStall.copy(
            owner = OwnerRef.guild("my-guild"),
            id = StallId("stall_guild")
        )
        val svc = buildService(stalls = listOf(guildStall))

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
}