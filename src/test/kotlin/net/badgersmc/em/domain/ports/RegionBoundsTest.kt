package net.badgersmc.em.domain.ports

import kotlin.test.Test
import kotlin.test.assertEquals

class RegionBoundsTest {

    @Test fun `dimensions are inclusive block spans`() {
        val b = RegionProvider.RegionBounds(minX = -52, minY = 120, minZ = -279, maxX = -47, maxY = 126, maxZ = -274)
        assertEquals(6, b.width)   // -52..-47 inclusive = 6
        assertEquals(7, b.height)  // 120..126 inclusive = 7
        assertEquals(6, b.length)  // -279..-274 inclusive = 6
    }
}
