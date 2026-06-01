package net.badgersmc.em.domain.stall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EntityLimitGroupTest {

    @Test fun `capFor returns per-type cap`() {
        val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to 5, "armor_stand" to 10))
        assertEquals(5, group.capFor("villager"))
        assertEquals(10, group.capFor("armor_stand"))
    }

    @Test fun `capFor returns -1 unlimited for unlisted type`() {
        val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to 5))
        assertEquals(-1, group.capFor("zombie"))
    }

    @Test fun `mergeExtras adds per-stall overrides on top of base caps`() {
        val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to 5))
        val merged = group.mergeExtras(extraTotal = 10, extraPerType = mapOf("villager" to 3, "armor_stand" to 2))
        assertEquals(60, merged.total)
        assertEquals(8, merged.capFor("villager"))
        assertEquals(2, merged.capFor("armor_stand"))
    }

    @Test fun `isOverCap true when count exceeds per-type cap`() {
        val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to 5))
        assertTrue(group.isOverTypeCap("villager", currentCount = 5))
        assertFalse(group.isOverTypeCap("villager", currentCount = 4))
    }

    @Test fun `unlimited per-type cap is never over`() {
        val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to -1))
        assertFalse(group.isOverTypeCap("villager", currentCount = 9999))
    }

    @Test fun `isOverTotal respects total cap and unlimited`() {
        val capped = EntityLimitGroup(total = 50, perType = emptyMap())
        assertTrue(capped.isOverTotal(currentTotal = 50))
        assertFalse(capped.isOverTotal(currentTotal = 49))
        val unlimited = EntityLimitGroup(total = -1, perType = emptyMap())
        assertFalse(unlimited.isOverTotal(currentTotal = 9999))
    }
}
