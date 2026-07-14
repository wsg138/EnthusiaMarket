package net.badgersmc.em.websync

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicNameResolverTest {
    private val uuid = UUID.fromString("00000000-0000-4000-8000-000000000001")

    @Test
    fun `missing cached owner names use safe public fallbacks while preserving identities`() {
        assertEquals("Unknown Player", PublicNameResolver.player(uuid) { null })
        assertEquals("Unknown Guild", PublicNameResolver.guild("guild-id") { null })
        assertNull(PublicNameResolver.delegatedMember(uuid) { null })
    }
}
