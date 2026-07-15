@file:Suppress("FunctionNaming")

package net.badgersmc.em.websync

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicOwnerAvatarResolverTest {
    @Test
    fun `java player uses stable UUID helm URL with outer-layer-capable endpoint`() {
        val uuid = UUID.fromString("00000000-0000-4000-8000-000000000099")
        val result = PublicOwnerAvatarResolver { false }.resolve(uuid, "SyntheticJava")

        assertEquals("JAVA", result.source)
        assertEquals("https://minotar.net/helm/$uuid/96.png", result.url)
        assertTrue(result.url!!.contains("/helm/"))
    }

    @Test
    fun `proxy UUID keeps generic fallback instead of querying a Java skin service`() {
        val uuid = UUID.fromString("00000000-0000-0000-0009-000000000001")
        val result = PublicOwnerAvatarResolver { false }.resolve(uuid, "*Synthetic Bedrock")

        assertEquals("PROXY", result.source)
        assertNull(result.url)
    }

    @Test
    fun `Floodgate identity keeps generic fallback until supported skin bytes are available`() {
        val uuid = UUID.randomUUID()
        val result = PublicOwnerAvatarResolver { true }.resolve(uuid, "SyntheticBedrock")

        assertEquals("FLOODGATE", result.source)
        assertNull(result.url)
    }

    @Test
    fun `unresolved Floodgate name preserves generic fallback`() {
        val result = PublicOwnerAvatarResolver { true }.resolve(UUID.randomUUID(), null)

        assertEquals("FLOODGATE", result.source)
        assertNull(result.url)
    }
}
