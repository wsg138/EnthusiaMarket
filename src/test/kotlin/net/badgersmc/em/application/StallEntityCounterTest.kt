package net.badgersmc.em.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class StallEntityCounterTest {

    @Test fun `increment and read cached count`() {
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager")
        counter.increment("stall1", "villager")
        assertEquals(2, counter.cachedCount("stall1", "villager"))
    }

    @Test fun `decrement never goes below zero`() {
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager")
        counter.decrement("stall1", "villager")
        counter.decrement("stall1", "villager")
        assertEquals(0, counter.cachedCount("stall1", "villager"))
    }

    @Test fun `cachedTotal sums all types in a stall`() {
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager")
        counter.increment("stall1", "armor_stand")
        assertEquals(2, counter.cachedTotal("stall1"))
    }

    @Test fun `recount replaces cache with authoritative scan`() {
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager") // stale cache says 1
        counter.recount("stall1", mapOf("villager" to 7, "armor_stand" to 2))
        assertEquals(7, counter.cachedCount("stall1", "villager"))
        assertEquals(2, counter.cachedCount("stall1", "armor_stand"))
        assertEquals(9, counter.cachedTotal("stall1"))
    }

    @Test fun `wouldExceedTypeCap uses authoritative recount at the boundary`() {
        val counter = StallEntityCounter()
        // Cache says 5 (at cap), but authoritative scan says only 3 -> allow.
        repeat(5) { counter.increment("stall1", "villager") }
        val authoritative = { _: String -> mapOf("villager" to 3) }
        val exceeded = counter.wouldExceedTypeCap("stall1", "villager", cap = 5, rescan = authoritative)
        assertFalse(exceeded)
        // After rescan, cache reflects the authoritative count.
        assertEquals(3, counter.cachedCount("stall1", "villager"))
    }

    @Test fun `wouldExceedTypeCap true when authoritative count still at cap`() {
        val counter = StallEntityCounter()
        repeat(5) { counter.increment("stall1", "villager") }
        val authoritative = { _: String -> mapOf("villager" to 5) }
        assertTrue(counter.wouldExceedTypeCap("stall1", "villager", cap = 5, rescan = authoritative))
    }

    @Test fun `unlimited cap never exceeds`() {
        val counter = StallEntityCounter()
        repeat(100) { counter.increment("stall1", "villager") }
        val exceeded = counter.wouldExceedTypeCap("stall1", "villager", cap = -1, rescan = { emptyMap() })
        assertFalse(exceeded)
    }
}
