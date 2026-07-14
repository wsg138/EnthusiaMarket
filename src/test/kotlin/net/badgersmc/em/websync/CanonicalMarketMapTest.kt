package net.badgersmc.em.websync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanonicalMarketMapTest {
    @Test
    fun `packages exact 71 identity-only records and pinned provenance`() {
        val map = CanonicalMarketMap.load()
        assertEquals(emptyList(), map.validate())
        assertEquals((1..71).map { "stall$it" }.toSet(), map.stalls.keys)
        assertNotNull(map.stalls["stall60"])
        assertNotNull(map.stalls["stall62"])
        assertEquals(CanonicalMarketMap.MAPPER_COMMIT, map.provenance.mapperCommit)
        val resource = javaClass.classLoader.getResource("market/canonical-market-stalls.json")!!.readText()
        listOf("polygon", "bounds", "center", "minX", "maxX").forEach { assertTrue(it !in resource) }
    }
}
