package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.AuctionRepository
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
        val config: EnthusiaMarketConfig,
        val auctionRepo: AuctionRepository
    )

    private fun buildService(
        stalls: List<Stall> = listOf(ownedStall),
        gracePeriod: String = "P3D"
    ): ServiceWithMocks {
        val stallRepo = mockk<StallRepository>(relaxUnitFun = true)
        every { stallRepo.all() } returns stalls

        val cfg = config(gracePeriod = gracePeriod)

        val shopRepo = mockk<net.badgersmc.em.domain.shop.ShopRepository>(relaxed = true)
        every { shopRepo.findByStall(any()) } returns emptyList()

        val auctionRepo = mockk<AuctionRepository>(relaxed = true)

        return ServiceWithMocks(
            service = RentCollectionService(stallRepo, shopRepo, cfg, auctionRepo, mockk()),
            stallRepo = stallRepo,
            shopRepo = shopRepo,
            config = cfg,
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
        val svc = buildService(stalls = listOf(graceStall), gracePeriod = "P3D")
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

    // --- tick: OWNED stall past due transitions to GRACE (no auto-charge) ---

    @Test
    fun `tick OWNED stall past due transitions to GRACE`() {
        val dueStall = ownedStall.copy(nextRentAt = now.minus(Duration.ofMinutes(1)))
        val svc = buildService(stalls = listOf(dueStall))

        val report = svc.service.tick(now)

        assertEquals(1, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.stallRepo.save(match { it.state == StallState.GRACE }) }
        verify { svc.shopRepo.freezeByStall(dueStall.id.value, true) }
    }

    // --- tick: long-owned stall gets its full grace window ---

    @Test
    fun `tick failed payment gives a long owned stall its full grace window`() {
        val graceStartedAt = now.plus(Duration.ofDays(10))
        val dueStall = ownedStall.copy(
            ownerSince = graceStartedAt.minus(Duration.ofDays(30)),
            nextRentAt = graceStartedAt.minus(Duration.ofMinutes(1)),
        )
        val svc = buildService(stalls = listOf(dueStall))

        val report = svc.service.tick(graceStartedAt)

        assertEquals(1, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        val saved = slot<Stall>()
        verify { svc.stallRepo.save(capture(saved)) }
        val graceStall = saved.captured
        assertEquals(StallState.GRACE, graceStall.state)
        assertEquals(dueStall.ownerSince, graceStall.ownerSince)
        assertEquals(dueStall.nextRentAt, graceStall.nextRentAt)
        val deadline = dueStall.nextRentAt!!.plus(Duration.ofDays(3))
        assertEquals(deadline, RentTimingPolicy.graceEndsAt(graceStall, svc.config))
        verify { svc.shopRepo.freezeByStall(dueStall.id.value, true) }

        every { svc.stallRepo.all() } returns listOf(graceStall)
        val beforeDeadline = svc.service.tick(deadline.minusSeconds(1))
        assertEquals(0, beforeDeadline.evictions)
        verify(exactly = 0) { svc.auctionRepo.create(any()) }

        val afterDeadline = svc.service.tick(deadline.plusSeconds(1))
        assertEquals(1, afterDeadline.evictions)
        verify(exactly = 1) { svc.auctionRepo.create(any()) }
    }

    // --- tick: OWNED stall with nextRentAt past grace → instant emergency auction ---

    @Test
    fun `tick OWNED stall past grace goes straight to emergency auction`() {
        val pastDue = ownedStall.copy(nextRentAt = now.minus(Duration.ofDays(4)))
        val svc = buildService(stalls = listOf(pastDue), gracePeriod = "P3D")

        val report = svc.service.tick()

        assertEquals(0, report.defaults)
        assertEquals(1, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.stallRepo.save(match {
            it.state == StallState.EMERGENCY_AUCTIONING
        }) }
        verify { svc.auctionRepo.create(any()) }
        verify { svc.shopRepo.freezeByStall(pastDue.id.value, true) }
    }

    // --- tick: GRACE + grace expired starts emergency auction ---

    @Test
    fun `tick GRACE and grace expired starts emergency auction`() {
        val svc = buildService(stalls = listOf(graceStall), gracePeriod = "P3D")

        val report = svc.service.tick()

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
        val svc = buildService(stalls = listOf(graceStall), gracePeriod = "P7D")

        val report = svc.service.tick(now)

        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)
    }

    // --- tick: UNOWNED stall is skipped ---

    @Test
    fun `tick UNOWNED stall is skipped`() {
        val svc = buildService(stalls = listOf(unownedStall))

        val report = svc.service.tick()

        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify(exactly = 0) { svc.stallRepo.save(any()) }
    }

    // --- tick: GRACE stall stays in GRACE (no auto-recovery) ---

    @Test
    fun `tick GRACE stall not past grace stays in GRACE`() {
        // Grace stall recently entered GRACE — shouldn't evict yet
        val freshGrace = graceStall.copy(
            nextRentAt = now
        )
        val svc = buildService(stalls = listOf(freshGrace), gracePeriod = "P3D")

        val report = svc.service.tick(now)

        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        // No auto-recovery to OWNED — player must right-click sign to extend
        verify(exactly = 0) { svc.stallRepo.save(any()) }
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

        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify(exactly = 0) { svc.stallRepo.save(any()) }
    }

    // --- tick: GUILD stall past due transitions to GRACE ---

    @Test
    fun `tick guild stall past due transitions to GRACE`() {
        val dueGuild = guildStall.copy(nextRentAt = now.minus(Duration.ofMinutes(1)))
        val svc = buildService(stalls = listOf(dueGuild))

        val report = svc.service.tick(now)

        assertEquals(1, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.stallRepo.save(match { it.state == StallState.GRACE }) }
    }

    // --- tick: GUILD stall past grace starts emergency auction ---

    @Test
    fun `tick guild stall past grace starts emergency auction`() {
        val svc = buildService(stalls = listOf(guildGraceStall), gracePeriod = "P3D")

        val report = svc.service.tick()

        assertEquals(0, report.defaults)
        assertEquals(1, report.evictions)
        assertEquals(0, report.errors)

        verify { svc.auctionRepo.create(any()) }
        verify { svc.stallRepo.save(match {
            it.state == StallState.EMERGENCY_AUCTIONING
        }) }
    }

    // --- tick: skips OWNED stall whose nextRentAt is in the future (C-10) ---

    @Test
    fun `tick skips OWNED stall whose nextRentAt is in the future`() {
        val prepaid = ownedStall.copy(nextRentAt = now.plus(Duration.ofDays(1)))
        val svc = buildService(stalls = listOf(prepaid))

        val report = svc.service.tick(now)

        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)

        verify(exactly = 0) { svc.stallRepo.save(any()) }
    }
}
