package net.badgersmc.em.application

import java.util.UUID
import kotlin.test.Test
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

    @Test fun `parseDuration handles off on 5m and garbage`() {
        assertTrue(BreakDeleteMode.parseDurationMs("off") == null)
        assertTrue(BreakDeleteMode.parseDurationMs("on") == 5L * 60_000)
        assertTrue(BreakDeleteMode.parseDurationMs("5m") == 5L * 60_000)
        assertTrue(BreakDeleteMode.parseDurationMs("10m") == 10L * 60_000)
        assertTrue(BreakDeleteMode.parseDurationMs("garbage") == 5L * 60_000)
    }
}