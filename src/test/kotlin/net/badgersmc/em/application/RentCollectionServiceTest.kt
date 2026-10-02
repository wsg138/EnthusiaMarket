package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.ports.MarketMutationGate
import net.badgersmc.em.domain.ports.RegionMemberSync
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
        gracePeriod: String = "P3D",
        mutationGate: MarketMutationGate = MarketMutationGate.Open,
    ): ServiceWithMocks {
        val stallRepo = mockk<StallRepository>(relaxUnitFun = true)
        every { stallRepo.all() } returns stalls

        val cfg = config(gracePeriod = gracePeriod)

        val shopRepo = mockk<net.badgersmc.em.domain.shop.ShopRepository>(relaxed = true)
        every { shopRepo.findByStall(any()) } returns emptyList()

        val auctionRepo = mockk<AuctionRepository>(relaxed = true)
        val lang = mockk<net.badgersmc.nexus.i18n.LangService>(relaxed = true)
        val regions = mockk<RegionMemberSync>(relaxed = true)
        val ipLimiter = mockk<IpLimiter>(relaxed = true)

        return ServiceWithMocks(
            service = newRentCollectionService(
                stallRepo = stallRepo,
                shopRepo = shopRepo,
                cfg = cfg,
                auctionRepo = auctionRepo,
                lang = lang,
                regions = regions,
                ipLimiter = ipLimiter,
                mutationGate = mutationGate,
            ),
            stallRepo = stallRepo,
            shopRepo = shopRepo,
            config = cfg,
            auctionRepo = auctionRepo
        )
    }

    private fun newRentCollectionService(
        stallRepo: StallRepository,
        shopRepo: net.badgersmc.em.domain.shop.ShopRepository,
        cfg: EnthusiaMarketConfig,
        auctionRepo: AuctionRepository,
        lang: net.badgersmc.nexus.i18n.LangService,
        regions: RegionMemberSync,
        ipLimiter: IpLimiter,
        mutationGate: MarketMutationGate,
        schematics: SchematicService = SchematicService.Disabled,
    ) = RentCollectionService(
        stallRepository = stallRepo,
        shops = shopRepo,
        config = cfg,
        auctionRepository = auctionRepo,
        lang = lang,
        regionMembers = regions,
        ipLimiter = ipLimiter,
        mutationGate = mutationGate,
        schematics = schematics,
    )

    private data class RecoveryLifecycleFixture(
        val service: RentCollectionService,
        val stallRepo: StallRepository,
        val shopRepo: net.badgersmc.em.domain.shop.ShopRepository,
        val auctionRepo: AuctionRepository,
        val regions: RegionMemberSync,
        val ipLimiter: IpLimiter,
    )

    private fun buildRecoveryLifecycleFixture(
        stalls: List<Stall>,
        mutationGate: MarketMutationGate,
        regions: RegionMemberSync = mockk(relaxed = true),
        ipLimiter: IpLimiter = mockk(relaxed = true),
    ): RecoveryLifecycleFixture {
        val stallRepo = mockk<StallRepository>(relaxUnitFun = true)
        every { stallRepo.all() } returns stalls
        val shopRepo = mockk<net.badgersmc.em.domain.shop.ShopRepository>(relaxed = true)
        every { shopRepo.findByStall(any()) } returns emptyList()
        val auctionRepo = mockk<AuctionRepository>(relaxed = true)
        val cfg = config()
        val lang = mockk<net.badgersmc.nexus.i18n.LangService>(relaxed = true)

        return RecoveryLifecycleFixture(
            service = newRentCollectionService(
                stallRepo = stallRepo,
                shopRepo = shopRepo,
                cfg = cfg,
                auctionRepo = auctionRepo,
                lang = lang,
                regions = regions,
                ipLimiter = ipLimiter,
                mutationGate = mutationGate,
            ),
            stallRepo = stallRepo,
            shopRepo = shopRepo,
            auctionRepo = auctionRepo,
            regions = regions,
            ipLimiter = ipLimiter,
        )
    }

    @Test
    fun `active rent enforcement skips moderation locked owned and grace stalls`() {
        val overdueOwned = ownedStall.copy(
            nextRentAt = now.minus(Duration.ofDays(5)),
        )
        val overdueGrace = graceStall.copy(
            nextRentAt = now.minus(Duration.ofDays(5)),
        )
        val lockedIds = setOf(overdueOwned.id.value, overdueGrace.id.value)
        val lockedGate = object : MarketMutationGate {
            override fun isStallLocked(stallId: String): Boolean = stallId in lockedIds
        }
        val svc = buildService(
            stalls = listOf(overdueOwned, overdueGrace),
            mutationGate = lockedGate,
        )

        val report = svc.service.tick(now)

        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        assertEquals(0, report.errors)
        verify(exactly = 0) { svc.shopRepo.freezeByStall(any(), any()) }
        verify(exactly = 0) { svc.stallRepo.save(any()) }
        verify(exactly = 0) { svc.auctionRepo.create(any()) }
    }

    // --- Emergency auction on grace expiry ---

    private fun shop(
        id: Long,
        stallId: String,
        adminShop: Boolean = false,
    ) = net.badgersmc.em.domain.shop.Shop(
        id = id, stallId = stallId, owner = playerUuid,
        signWorld = "world", signX = 0, signY = 64, signZ = 0,
        containerWorld = "world", containerX = 0, containerY = 63, containerZ = 0,
        sellItem = "item", sellAmount = 1, costItem = "item", costAmount = 1,
        adminShop = adminShop,
    )

    private data class EmergencyForfeitureFixture(
        val service: RentCollectionService,
        val stallRepo: StallRepository,
        val shopRepo: net.badgersmc.em.domain.shop.ShopRepository,
        val auctionRepo: AuctionRepository,
        val regions: RegionMemberSync,
        val ipLimiter: IpLimiter,
        val schematics: SchematicService,
    )

    @Suppress("CyclomaticComplexMethod")
    private fun buildEmergencyForfeitureFixture(
        stalls: List<Stall>,
        regions: RegionMemberSync = mockk(relaxed = true),
        ipLimiter: IpLimiter = mockk(relaxed = true),
        schematics: SchematicService = mockk(relaxed = true),
    ): EmergencyForfeitureFixture {
        val stallRepo = mockk<StallRepository>(relaxUnitFun = true)
        every { stallRepo.all() } returns stalls
        val shopRepo = mockk<net.badgersmc.em.domain.shop.ShopRepository>(relaxed = true)
        every { shopRepo.findByStall(any()) } returns emptyList()
        val auctionRepo = mockk<AuctionRepository>(relaxed = true)
        val cfg = config().apply { this.schematics.enabled = true }
        val lang = mockk<net.badgersmc.nexus.i18n.LangService>(relaxed = true)

        return EmergencyForfeitureFixture(
            service = newRentCollectionService(
                stallRepo = stallRepo,
                shopRepo = shopRepo,
                cfg = cfg,
                auctionRepo = auctionRepo,
                lang = lang,
                regions = regions,
                ipLimiter = ipLimiter,
                mutationGate = MarketMutationGate.Open,
                schematics = schematics,
            ),
            stallRepo = stallRepo,
            shopRepo = shopRepo,
            auctionRepo = auctionRepo,
            regions = regions,
            ipLimiter = ipLimiter,
            schematics = schematics,
        )
    }

    @Test
    fun `emergency forfeiture cleans previous ownership only after authoritative save`() {
        val staleMember = UUID.fromString("00000000-0000-0000-0000-000000000005")
        val forfeited = graceStall.copy(
            members = setOf(staleMember),
            nextRentAt = now.minus(Duration.ofDays(4)),
        )
        val normalShop = shop(41L, forfeited.id.value)
        val adminShop = shop(42L, forfeited.id.value, adminShop = true)
        val fixture = buildEmergencyForfeitureFixture(stalls = listOf(forfeited))
        every { fixture.shopRepo.findByStall(forfeited.id.value) } returns listOf(normalShop, adminShop)
        every {
            fixture.schematics.restore(forfeited.id.value, forfeited.world, forfeited.regionId)
        } returns SchematicService.Result.Success

        val report = fixture.service.tick(now)

        assertEquals(1, report.evictions)
        assertEquals(0, report.errors)
        verifyOrder {
            fixture.stallRepo.save(match {
                it.state == StallState.EMERGENCY_AUCTIONING &&
                    it.owner == forfeited.owner &&
                    it.members.isEmpty()
            })
            fixture.auctionRepo.create(any())
            fixture.ipLimiter.releaseStallByOwnerId(playerUuid.toString())
            fixture.shopRepo.findByStall(forfeited.id.value)
            fixture.shopRepo.delete(41L)
            fixture.regions.clearOwnersAndMembers(forfeited.world, forfeited.regionId)
            fixture.schematics.restore(forfeited.id.value, forfeited.world, forfeited.regionId)
        }
        verify(exactly = 0) { fixture.shopRepo.delete(42L) }

        val raced = buildEmergencyForfeitureFixture(stalls = listOf(forfeited))
        every { raced.stallRepo.save(any()) } throws IllegalStateException("moderation fence won the race")
        every { raced.shopRepo.findByStall(forfeited.id.value) } returns listOf(normalShop)
        every {
            raced.schematics.restore(forfeited.id.value, forfeited.world, forfeited.regionId)
        } returns SchematicService.Result.Success

        val racedReport = raced.service.tick(now)

        assertEquals(0, racedReport.evictions)
        assertEquals(1, racedReport.errors)
        verify(exactly = 0) { raced.ipLimiter.releaseStallByOwnerId(any()) }
        verify(exactly = 0) { raced.shopRepo.findByStall(any()) }
        verify(exactly = 0) { raced.regions.clearOwnersAndMembers(any(), any()) }
        verify(exactly = 0) { raced.schematics.restore(any(), any(), any()) }
        verify(exactly = 0) { raced.auctionRepo.create(any()) }
    }

    @Test
    fun `emergency auction creation failure does not destroy previous ownership projections`() {
        val forfeited = graceStall.copy(
            members = setOf(UUID.fromString("00000000-0000-0000-0000-000000000005")),
            nextRentAt = now.minus(Duration.ofDays(4)),
        )
        val fixture = buildEmergencyForfeitureFixture(stalls = listOf(forfeited))
        every { fixture.auctionRepo.create(any()) } throws IllegalStateException("synthetic auction insert failure")

        val report = fixture.service.tick(now)

        assertEquals(0, report.evictions)
        assertEquals(1, report.errors)
        verify(exactly = 1) {
            fixture.stallRepo.save(match {
                it.state == StallState.EMERGENCY_AUCTIONING &&
                    it.owner == forfeited.owner &&
                    it.members.isEmpty()
            })
        }
        verify(exactly = 0) { fixture.ipLimiter.releaseStallByOwnerId(any()) }
        verify(exactly = 0) { fixture.shopRepo.findByStall(any()) }
        verify(exactly = 0) { fixture.regions.clearOwnersAndMembers(any(), any()) }
        verify(exactly = 0) { fixture.schematics.restore(any(), any(), any()) }
    }

    @Test
    fun `orphan recovery clears projections and respects moderation fencing`() {
        val orphan = ownedStall.copy(
            state = StallState.EMERGENCY_AUCTIONING,
            ownerSince = now.minus(Duration.ofDays(30)),
            winningBid = 2_500L,
            nextRentAt = now.minus(Duration.ofDays(3)),
        )

        val unlocked = buildRecoveryLifecycleFixture(
            stalls = listOf(orphan),
            mutationGate = MarketMutationGate.Open,
        )
        every { unlocked.auctionRepo.findOpenByStall(orphan.id) } returns null

        unlocked.service.tick(now)

        verify(exactly = 1) {
            unlocked.regions.clearOwnersAndMembers(orphan.world, orphan.regionId)
        }
        verify(exactly = 1) {
            unlocked.ipLimiter.releaseStallByOwnerId(playerUuid.toString())
        }

        val lockedGate = object : MarketMutationGate {
            override fun isStallLocked(stallId: String): Boolean = stallId == orphan.id.value
        }
        val locked = buildRecoveryLifecycleFixture(
            stalls = listOf(orphan),
            mutationGate = lockedGate,
        )
        every { locked.auctionRepo.findOpenByStall(orphan.id) } returns null

        locked.service.tick(now)

        verify(exactly = 0) { locked.stallRepo.save(any()) }
        verify(exactly = 0) { locked.shopRepo.findByStall(any()) }
        verify(exactly = 0) {
            locked.regions.clearOwnersAndMembers(any(), any())
        }
        verify(exactly = 0) {
            locked.ipLimiter.releaseStallByOwnerId(any())
        }

        val raced = buildRecoveryLifecycleFixture(
            stalls = listOf(orphan),
            mutationGate = MarketMutationGate.Open,
        )
        every { raced.auctionRepo.findOpenByStall(orphan.id) } returns null
        every { raced.stallRepo.save(any()) } throws IllegalStateException("moderation fence won the race")
        every { raced.shopRepo.findByStall(orphan.id.value) } returns listOf(shop(31L, orphan.id.value))

        raced.service.tick(now)

        verify(exactly = 0) { raced.shopRepo.delete(any()) }
        verify(exactly = 0) {
            raced.regions.clearOwnersAndMembers(any(), any())
        }
        verify(exactly = 0) {
            raced.ipLimiter.releaseStallByOwnerId(any())
        }
    }

    @Test
    fun `orphaned emergency auction recovery clears stale ownership and non admin shops idempotently`() {
        val staleMember = UUID.fromString("00000000-0000-0000-0000-000000000004")
        val orphan = ownedStall.copy(
            state = StallState.EMERGENCY_AUCTIONING,
            ownerSince = now.minus(Duration.ofDays(30)),
            winningBid = 2_500L,
            members = setOf(staleMember),
            nextRentAt = now.minus(Duration.ofDays(3)),
        )
        val normalShop = shop(21L, orphan.id.value)
        val adminShop = shop(22L, orphan.id.value, adminShop = true)
        val svc = buildService(stalls = listOf(orphan))
        every { svc.auctionRepo.findOpenByStall(orphan.id) } returns null
        every { svc.shopRepo.findByStall(orphan.id.value) } returns listOf(normalShop, adminShop)

        svc.service.tick(now)

        verify(exactly = 1) {
            svc.stallRepo.save(match {
                it.state == StallState.UNOWNED &&
                    it.owner == OwnerRef.unowned() &&
                    it.ownerSince == null &&
                    it.winningBid == 0L &&
                    it.members.isEmpty() &&
                    it.nextRentAt == null
            })
        }
        verify(exactly = 1) { svc.shopRepo.delete(21L) }
        verify(exactly = 0) { svc.shopRepo.delete(22L) }

        every { svc.stallRepo.all() } returns listOf(orphan.releaseOwnership())
        svc.service.tick(now)

        verify(exactly = 1) {
            svc.stallRepo.save(match {
                it.state == StallState.UNOWNED && it.owner == OwnerRef.unowned()
            })
        }
        verify(exactly = 1) { svc.shopRepo.delete(21L) }
    }

    @Test
    fun `tick past grace cleans non admin shops before emergency auction`() {
        val svc = buildService(stalls = listOf(graceStall), gracePeriod = "P3D")
        every { svc.shopRepo.findByStall("stall_02") } returns
            listOf(shop(11, "stall_02"), shop(12, "stall_02", adminShop = true))

        val report = svc.service.tick()

        assertEquals(1, report.evictions)
        verify(exactly = 1) { svc.shopRepo.delete(11L) }
        verify(exactly = 0) { svc.shopRepo.delete(12L) }
        verify { svc.auctionRepo.create(any()) }
        verify { svc.stallRepo.save(match {
            it.state == StallState.EMERGENCY_AUCTIONING &&
                it.owner == graceStall.owner &&
                it.members.isEmpty()
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
