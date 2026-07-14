package net.badgersmc.em.websync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailingDebounceTest {
    @Test
    fun `continuous changes cannot postpone beyond two seconds`() {
        val debounce = TrailingDebounce(250, 2000)
        debounce.mark("stall1", 0)
        for (now in 100L..1900L step 100) debounce.mark("stall1", now)
        assertNull(debounce.firstReady(1999))
        assertEquals("stall1", debounce.firstReady(2000))
    }
}
