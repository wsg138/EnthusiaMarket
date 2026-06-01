package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.StallEntityCounter
import net.badgersmc.em.domain.stall.EntityLimitGroup
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EntityLimitListenerTest {

    private val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to 5))

    @Test fun `cancels when type at cap and authoritative confirms`() {
        val counter = StallEntityCounter()
        repeat(5) { counter.increment("stall1", "villager") }
        val cancel = EntityLimitListener.decide(
            "stall1", "villager", group, counter, rescan = { mapOf("villager" to 5) }
        )
        assertTrue(cancel)
    }

    @Test fun `allows when under type cap`() {
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager")
        val cancel = EntityLimitListener.decide(
            "stall1", "villager", group, counter, rescan = { mapOf("villager" to 1) }
        )
        assertFalse(cancel)
    }

    @Test fun `cancels when total at cap even if type under`() {
        val smallTotal = EntityLimitGroup(total = 2, perType = mapOf("villager" to 99))
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager")
        counter.increment("stall1", "armor_stand")
        val cancel = EntityLimitListener.decide(
            "stall1", "villager", smallTotal, counter, rescan = { mapOf("villager" to 1, "armor_stand" to 1) }
        )
        assertTrue(cancel)
    }

    @Test fun `allows on increment when accepted`() {
        val counter = StallEntityCounter()
        val cancel = EntityLimitListener.decide(
            "stall1", "villager", group, counter, rescan = { emptyMap() }
        )
        assertFalse(cancel)
        // Accepted spawn bumps the cache.
        assertTrue(counter.cachedCount("stall1", "villager") >= 1)
    }
}