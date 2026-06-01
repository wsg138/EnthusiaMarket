package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.RegionProvider.RegionBounds
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ParticleBorderServiceTest {

    @Test fun `total particles never exceed maxPerTick`() {
        val outlines = listOf(
            RegionBounds(0, 0, 0, 10, 5, 10),
            RegionBounds(0, 0, 0, 50, 20, 50),
        )
        val plan = ParticleBorderService.planParticles(outlines, maxPerTick = 200)
        val total = plan.sumOf { it.points.size }
        assertTrue(total <= 200, "total $total exceeded budget 200")
    }

    @Test fun `single small region within budget gets dense outline`() {
        val outlines = listOf(RegionBounds(0, 0, 0, 4, 4, 4))
        val plan = ParticleBorderService.planParticles(outlines, maxPerTick = 200)
        assertTrue(plan.single().points.isNotEmpty())
        assertTrue(plan.single().points.size <= 200)
    }

    @Test fun `empty outline list yields empty plan`() {
        assertEquals(emptyList(), ParticleBorderService.planParticles(emptyList(), maxPerTick = 200))
    }

    @Test fun `zero budget yields no points`() {
        val outlines = listOf(RegionBounds(0, 0, 0, 10, 10, 10))
        val plan = ParticleBorderService.planParticles(outlines, maxPerTick = 0)
        assertEquals(0, plan.sumOf { it.points.size })
    }
}