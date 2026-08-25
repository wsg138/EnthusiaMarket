package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StallVolumePricingServiceTest {

    private fun config(
        enabled: Boolean = true,
        baseFlat: Long = 50,
        perBlock: Double = 0.5,
    ): EnthusiaMarketConfig = EnthusiaMarketConfig().apply {
        pricing.enabled = enabled
        pricing.baseFlat = baseFlat
        pricing.perBlock = perBlock
    }

    private fun stall(id: String, terms: RentTerms) = Stall(
        id = StallId(id), regionId = id, world = "world",
        state = StallState.UNOWNED, owner = net.badgersmc.em.domain.stall.OwnerRef.unowned(),
        ownerSince = null, winningBid = 0L, rentTerms = terms,
    )

    private fun service(
        cfg: EnthusiaMarketConfig = config(),
        bounds: Map<String, RegionProvider.RegionBounds> = emptyMap(),
        existing: List<Stall> = emptyList(),
    ): Triple<StallVolumePricingService, StallRepository, RegionProvider> {
        val regionProvider = mockk<RegionProvider>()
        every { regionProvider.bounds("world", any()) } answers {
            bounds[secondArg<String>()]
        }
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.all() } returns existing
        every { repo.findById(any()) } answers {
            // StallId is a @JvmInline value class — the raw arg is the underlying
            // String; casting through firstArg<StallId>() throws ClassCastException.
            val rawId = invocation.args[0].toString()
            existing.firstOrNull { it.id.value == rawId }
        }
        val svc = StallVolumePricingService(regionProvider, repo, cfg)
        return Triple(svc, repo, regionProvider)
    }

    @Test
    fun `volume is the inclusive block count of the region cuboid`() {
        val (svc, _, _) = service(
            bounds = mapOf("stall1" to RegionProvider.RegionBounds(0, 0, 0, 5, 3, 4))
        )
        assertEquals(6L * 4L * 5L, svc.volumeOf("world", "stall1"))
    }

    @Test
    fun `volume does not overflow Int for huge regions`() {
        // 2048 × 512 × 2048 = 2_147_483_648 — overflows Int.MAX_VALUE (2_147_483_647).
        val (svc, _, _) = service(
            bounds = mapOf("huge" to RegionProvider.RegionBounds(0, 0, 0, 2047, 511, 2047))
        )
        assertEquals(2_147_483_648L, svc.volumeOf("world", "huge"))
        // And the rent stays positive + sane.
        assertEquals(1_073_741_874L, svc.rentFor(2_147_483_648L))
    }

    @Test
    fun `volumeOf returns null when the region has no bounds`() {
        val (svc, _, _) = service()
        assertNull(svc.volumeOf("world", "missing"))
    }

    @Test
    fun `rentFor is base plus volume times per-block rate`() {
        val (svc, _, _) = service(config(baseFlat = 50, perBlock = 0.5))
        assertEquals(50L, svc.rentFor(0))
        assertEquals(100L, svc.rentFor(100))
        assertEquals(250L, svc.rentFor(400))
    }

    @Test
    fun `preview lists every stall with its computed rent without writing`() {
        val bounds = mapOf(
            "stall1" to RegionProvider.RegionBounds(0, 0, 0, 5, 3, 4),   // 6*4*5 = 120 → 110
            "stall2" to RegionProvider.RegionBounds(0, 0, 0, 1, 1, 1),    // 2*2*2 = 8 → 54
        )
        val (svc, repo, _) = service(
            existing = listOf(stall("stall1", RentTerms.flat(100)), stall("stall2", RentTerms.flat(100))),
            bounds = bounds,
        )
        val rows = svc.preview("world", "stall")
        assertEquals(2, rows.size)
        assertEquals("stall1", rows[0].stallId)
        assertEquals(120L, rows[0].volume)
        assertEquals(110L, rows[0].computedRent)
        assertEquals("stall2", rows[1].stallId)
        assertEquals(8L, rows[1].volume)
        assertEquals(54L, rows[1].computedRent)
        verify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `apply rewrites differing terms and skips matching ones`() {
        val bounds = mapOf(
            "stall1" to RegionProvider.RegionBounds(0, 0, 0, 5, 3, 4),   // 120 → 110
            "stall2" to RegionProvider.RegionBounds(0, 0, 0, 1, 1, 1),    // 8 → 54
        )
        val existing = listOf(
            stall("stall1", RentTerms.flat(100)),
            stall("stall2", RentTerms.flat(54)),
        )
        val (svc, repo, _) = service(existing = existing, bounds = bounds)
        val saved = mutableListOf<Stall>()
        every { repo.save(capture(saved)) } returns Unit

        val changed = svc.apply("world", "stall")

        assertEquals(1, changed)
        assertEquals(listOf("stall1"), saved.map { it.id.value })
        assertEquals(RentTerms.flat(110), saved.single().rentTerms)
    }

    @Test
    fun `apply is a no-op when pricing is disabled`() {
        val bounds = mapOf(
            "stall1" to RegionProvider.RegionBounds(0, 0, 0, 5, 3, 4),
        )
        val (svc, repo, _) = service(
            cfg = config(enabled = false),
            existing = listOf(stall("stall1", RentTerms.flat(100))),
            bounds = bounds,
        )
        val changed = svc.apply("world", "stall")
        assertEquals(0, changed)
        verify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `termsFor returns volume terms when enabled and null when disabled`() {
        val bounds = mapOf(
            "stall1" to RegionProvider.RegionBounds(0, 0, 0, 5, 3, 4),   // 120 → 110
        )
        val (svc, _, _) = service(bounds = bounds)
        assertEquals(RentTerms.flat(110), svc.termsFor("world", "stall1"))

        val (disabledSvc, _, _) = service(cfg = config(enabled = false), bounds = bounds)
        assertNull(disabledSvc.termsFor("world", "stall1"))
    }

    @Test
    fun `config rejects negative or non-finite pricing values`() {
        assertFailsWith<IllegalArgumentException> { EnthusiaMarketConfig().apply { pricing.baseFlat = -1 } }
        assertFailsWith<IllegalArgumentException> { EnthusiaMarketConfig().apply { pricing.perBlock = -0.5 } }
        assertFailsWith<IllegalArgumentException> { EnthusiaMarketConfig().apply { pricing.perBlock = Double.NaN } }
        assertFailsWith<IllegalArgumentException> { EnthusiaMarketConfig().apply { pricing.perBlock = Double.POSITIVE_INFINITY } }
    }
}
