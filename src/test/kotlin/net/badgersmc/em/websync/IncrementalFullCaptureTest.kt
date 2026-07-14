package net.badgersmc.em.websync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncrementalFullCaptureTest {
    @Test
    fun `full capture processes one stall per tick and never zero from exhausted budget`() {
        var clock = 10L
        val capture = IncrementalFullCapture(
            stallIds = listOf("stall1", "stall2", "stall3"),
            generation = { 0 },
            capture = ::stall,
            timeBudgetNanos = 0,
            nanoTime = { clock++ },
        )
        assertEquals(1, capture.tick())
        assertFalse(capture.complete)
        assertEquals(1, capture.tick())
        assertEquals(1, capture.tick())
        assertTrue(capture.complete)
    }

    @Test
    fun `records generation at each stall capture for post-capture dirty barrier`() {
        val generations = mutableMapOf("stall1" to 2L, "stall2" to 4L)
        val capture = IncrementalFullCapture(listOf("stall1", "stall2"), { generations[it]!! }, ::stall)
        capture.tick()
        generations["stall1"] = 3
        capture.tick()
        assertEquals(2, capture.capturedGenerations["stall1"])
        assertTrue(generations["stall1"]!! > capture.capturedGenerations["stall1"]!!)
    }

    private fun stall(id: String) = PublicStall(
        id, "building-1", 1, PublicLocation("world", 0, 64, 0),
        PublicOwner("NONE", null, null, "Unowned", avatar = PublicAvatar("NONE")),
        null, null, emptyList(), emptyList(),
    )
}
