package net.badgersmc.em.application

import net.badgersmc.em.domain.stall.EntityLimitGroup
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityLimitConfigTest {

    private val yaml = """
        default:
          _total: 50
          villager: 5
          armor_stand: 10
        shop:
          _total: 80
          villager: 10
    """.trimIndent()

    @Test fun `parses groups keyed by kind`() {
        val groups = EntityLimitConfig.parse(yaml)
        assertEquals(50, groups.getValue("default").total)
        assertEquals(5, groups.getValue("default").capFor("villager"))
        assertEquals(10, groups.getValue("default").capFor("armor_stand"))
        assertEquals(80, groups.getValue("shop").total)
        assertEquals(10, groups.getValue("shop").capFor("villager"))
    }

    @Test fun `groupFor returns default group when kind missing`() {
        val groups = EntityLimitConfig.parse(yaml)
        val resolved = EntityLimitConfig.groupFor(groups, "nonexistent")
        assertEquals(groups.getValue("default"), resolved)
    }

    @Test fun `groupFor returns empty unlimited group when no default present`() {
        val groups = EntityLimitConfig.parse("shop:\n  _total: 80")
        val resolved = EntityLimitConfig.groupFor(groups, "unknown")
        assertEquals(EntityLimitGroup(total = -1, perType = emptyMap()), resolved)
    }
}
