package net.badgersmc.em.domain.stall

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OwnerRefTest {
    @Test fun `solo owner ref carries a player UUID`() {
        val uuid = UUID.randomUUID()
        val ref = OwnerRef.solo(uuid)
        assertEquals(OwnerType.SOLO, ref.type)
        assertEquals(uuid.toString(), ref.id)
    }

    @Test fun `guild owner ref carries a guild id`() {
        val ref = OwnerRef.guild("guild-42")
        assertEquals(OwnerType.GUILD, ref.type)
        assertEquals("guild-42", ref.id)
    }

    @Test fun `unowned ref has no id`() {
        val ref = OwnerRef.unowned()
        assertEquals(OwnerType.NONE, ref.type)
        assertEquals("", ref.id)
    }

    @Test fun `solo ref rejects blank id`() {
        assertFailsWith<IllegalArgumentException> { OwnerRef(OwnerType.SOLO, "") }
    }
}
