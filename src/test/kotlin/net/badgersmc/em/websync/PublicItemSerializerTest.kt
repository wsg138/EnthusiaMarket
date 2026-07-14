package net.badgersmc.em.websync

import kotlin.test.Test
import kotlin.test.assertEquals

class PublicItemSerializerTest {
    @Test
    fun `potion ticks convert deterministically by flooring whole seconds`() {
        assertEquals(0, PublicItemSerializer.ticksToSeconds(19))
        assertEquals(1, PublicItemSerializer.ticksToSeconds(20))
        assertEquals(1, PublicItemSerializer.ticksToSeconds(39))
        assertEquals(2, PublicItemSerializer.ticksToSeconds(40))
        assertEquals(0, PublicItemSerializer.ticksToSeconds(-1))
    }
}
