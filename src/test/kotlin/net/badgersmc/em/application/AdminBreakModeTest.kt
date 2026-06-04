package net.badgersmc.em.application

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminBreakModeTest {

    @Test fun `enable makes it active within the window`() {
        val m = AdminBreakMode()
        val u = UUID.randomUUID()
        m.enable(u, 60_000, nowMs = 0)
        assertTrue(m.isActive(u, nowMs = 1_000))
    }

    @Test fun `disable clears it`() {
        val m = AdminBreakMode()
        val u = UUID.randomUUID()
        m.enable(u, 60_000, nowMs = 0)
        m.disable(u)
        assertFalse(m.isActive(u, nowMs = 1_000))
    }

    @Test fun `expires after the window`() {
        val m = AdminBreakMode()
        val u = UUID.randomUUID()
        m.enable(u, 60_000, nowMs = 0)
        assertFalse(m.isActive(u, nowMs = 60_001))
    }

    @Test fun `unknown player is inactive`() {
        assertFalse(AdminBreakMode().isActive(UUID.randomUUID()))
    }
}