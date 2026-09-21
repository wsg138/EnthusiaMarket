package net.badgersmc.em.application

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BreakDeleteModeTest {

    @Test fun `enable makes it active within the window`() {
        val mode = BreakDeleteMode()
        val p = UUID.randomUUID()
        mode.enable(p, durationMs = 1000, nowMs = 0)
        assertTrue(mode.isActive(p, nowMs = 500))
    }

    @Test fun `expires after the window and purges`() {
        val mode = BreakDeleteMode()
        val p = UUID.randomUUID()
        mode.enable(p, durationMs = 1000, nowMs = 0)
        assertFalse(mode.isActive(p, nowMs = 1001))
        // second read confirms purge did not throw / re-activate
        assertFalse(mode.isActive(p, nowMs = 2000))
    }

    @Test fun `disable turns it off`() {
        val mode = BreakDeleteMode()
        val p = UUID.randomUUID()
        mode.enable(p, durationMs = 10000, nowMs = 0)
        mode.disable(p)
        assertFalse(mode.isActive(p, nowMs = 1))
    }

    @Test fun `per-player isolation`() {
        val mode = BreakDeleteMode()
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        mode.enable(a, durationMs = 1000, nowMs = 0)
        assertTrue(mode.isActive(a, nowMs = 1))
        assertFalse(mode.isActive(b, nowMs = 1))
    }

    @Test fun `parseDuration normalizes and prioritizes mode values`() {
        assertTrue(BreakDeleteMode.parseDurationMs("off") == null)
        assertTrue(BreakDeleteMode.parseDurationMs(" OFF ") == null)
        assertEquals(DEFAULT_MS, BreakDeleteMode.parseDurationMs(null))
        assertEquals(DEFAULT_MS, BreakDeleteMode.parseDurationMs("on"))
        assertEquals(DEFAULT_MS, BreakDeleteMode.parseDurationMs(" ON "))
        assertEquals(DEFAULT_MS, BreakDeleteMode.parseDurationMs(""))
        assertEquals(DEFAULT_MS, BreakDeleteMode.parseDurationMs("   "))
    }

    @Test fun `parseDuration accepts positive minute values`() {
        assertEquals(5L * MINUTE_MS, BreakDeleteMode.parseDurationMs("5m"))
        assertEquals(10L * MINUTE_MS, BreakDeleteMode.parseDurationMs("10M"))
        assertEquals(5L * MINUTE_MS, BreakDeleteMode.parseDurationMs(" 5m "))
    }

    @Test fun `parseDuration falls back for invalid minute values`() {
        assertEquals(DEFAULT_MS, BreakDeleteMode.parseDurationMs("0m"))
        assertEquals(DEFAULT_MS, BreakDeleteMode.parseDurationMs("-1m"))
        assertEquals(DEFAULT_MS, BreakDeleteMode.parseDurationMs("m"))
        assertEquals(DEFAULT_MS, BreakDeleteMode.parseDurationMs("garbage"))
        assertEquals(DEFAULT_MS, BreakDeleteMode.parseDurationMs("9223372036854775808m"))
    }

    @Test fun `parseDuration keeps long multiplication overflow behavior`() {
        assertEquals(
            Long.MAX_VALUE * MINUTE_MS,
            BreakDeleteMode.parseDurationMs("${Long.MAX_VALUE}m")
        )
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DEFAULT_MS = 5L * MINUTE_MS
    }
}
