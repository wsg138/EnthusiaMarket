package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.ports.SchematicService
import net.badgersmc.em.domain.shop.ShopRepository
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
 * TDD-271 — schematic restore on unclaim (REQ-271, REQ-272, REQ-273).
 *
 * When a stall transitions back to UNOWNED via rent eviction or voluntary
 * sellback, the stored snapshot must be pasted back over the region to
 * restore the original geometry. Honour `schematics.enabled: false`.
 */
class SchematicRestoreTest {

    private val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val now = Instant.parse("2026-05-24T10:00:00Z")

    private fun config(enabled: Boolean = true): EnthusiaMarketConfig =
        EnthusiaMarketConfig().apply {
            rent.mode = "flat"
            rent.flatAmount = 50L
            rent.collectionInterval = "P1D"
            rent.gracePeriod = "P3D"
            schematics.enabled = enabled
        }

    // --- Rent eviction restores geometry --------------------------------

    private val graceStall = Stall(
        id = StallId("stall_02"),
        regionId = "stall_02",
        world = "world",
        state = StallState.GRACE,
        owner = OwnerRef.solo(owner),
        ownerSince = now.minus(Duration.ofDays(5)),
        winningBid = 1000L,
        rentTerms = RentTerms.flat(50L),
    )

    @Test
    fun `eviction restores the stored schematic`() {
        val stallRepo = mockk<StallRepository>(relaxUnitFun = true)
        every { stallRepo.all() } returns listOf(graceStall)
        val economy = mockk<EconomyProvider>()
        every { economy.withdraw(any(), any()) } returns false
        val schematics = mockk<SchematicService>(relaxed = true)

        val service = RentCollectionService(
            stallRepo, mockk<SellOfferRepository>(relaxed = true), economy, mockk<GuildProvider>(relaxed = true), config(), mockk(relaxed = true), schematics,
        )

        val report = service.tick(now)

        // Sanity: the stall was actually evicted.
        require(report.evictions == 1)
        verify(exactly = 1) { schematics.restore("stall_02", "world", "stall_02") }
    }

    @Test
    fun `eviction skips restore when snapshots disabled`() {
        val stallRepo = mockk<StallRepository>(relaxUnitFun = true)
        every { stallRepo.all() } returns listOf(graceStall)
        val economy = mockk<EconomyProvider>()
        every { economy.withdraw(any(), any()) } returns false
        val schematics = mockk<SchematicService>(relaxed = true)

        val service = RentCollectionService(
            stallRepo, mockk<SellOfferRepository>(relaxed = true), economy, mockk<GuildProvider>(relaxed = true), config(enabled = false), mockk(relaxed = true), schematics,
        )

        service.tick(now)

        verify(exactly = 0) { schematics.restore(any(), any(), any()) }
    }

    // --- Voluntary sellback restores geometry ---------------------------

    private val ownedStall = Stall(
        id = StallId("stall_01"),
        regionId = "stall_01",
        world = "world",
        state = StallState.OWNED,
        owner = OwnerRef.solo(owner),
        ownerSince = now.minus(Duration.ofDays(1)),
        winningBid = 0L,
        rentTerms = RentTerms.flat(0L),
    )

    private fun buildSellback(
        enabled: Boolean,
        schematics: SchematicService,
    ): StallSellbackService {
        val stalls = mockk<StallRepository>(relaxUnitFun = true)
        every { stalls.findById(StallId("stall_01")) } returns ownedStall
        val shops = mockk<ShopRepository>(relaxed = true)
        every { shops.findByStall(any()) } returns emptyList()
        val economy = mockk<EconomyProvider>()
        every { economy.deposit(any(), any()) } returns true
        val guilds = mockk<GuildProvider>(relaxed = true)
        val regionMembers = mockk<RegionMemberSync>(relaxed = true)
        return StallSellbackService(
            stalls, shops, mockk<SellOfferRepository>(relaxed = true), economy, guilds, config(enabled = enabled), regionMembers, schematics,
        )
    }

    @Test
    fun `sellback restores the stored schematic`() {
        val schematics = mockk<SchematicService>(relaxed = true)
        val service = buildSellback(enabled = true, schematics = schematics)

        val result = service.execute(StallId("stall_01"), owner)

        require(result is StallSellbackService.ExecuteResult.Sold)
        verify(exactly = 1) { schematics.restore("stall_01", "world", "stall_01") }
    }

    @Test
    fun `sellback skips restore when snapshots disabled`() {
        val schematics = mockk<SchematicService>(relaxed = true)
        val service = buildSellback(enabled = false, schematics = schematics)

        service.execute(StallId("stall_01"), owner)

        verify(exactly = 0) { schematics.restore(any(), any(), any()) }
    }
}
