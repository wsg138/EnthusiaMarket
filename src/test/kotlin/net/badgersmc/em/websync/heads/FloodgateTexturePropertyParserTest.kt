package net.badgersmc.em.websync.heads

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FloodgateTexturePropertyParserTest {
    private val hash = "a".repeat(64)

    @Test
    fun `accepts a normalized Mojang texture URL`() {
        assertEquals(hash, FloodgateTexturePropertyParser.parse(property("https://textures.minecraft.net/texture/$hash"))?.hash)
    }

    @Test
    fun `rejects urls that could reach another host`() {
        listOf(
            "http://textures.minecraft.net/texture/$hash?next=x",
            "https://textures.minecraft.net.evil.test/texture/$hash",
            "https://textures.minecraft.net@evil.test/texture/$hash",
            "https://textures.minecraft.net/texture/${"A".repeat(64)}",
        ).forEach { assertNull(FloodgateTexturePropertyParser.parse(property(it))) }
    }

    @Test
    fun `rejects overly nested JSON before parsing`() {
        val nested = "[".repeat(17) + "0" + "]".repeat(17)
        assertNull(FloodgateTexturePropertyParser.parse(Base64.getEncoder().encodeToString(nested.toByteArray())))
    }

    private fun property(url: String): String = Base64.getEncoder().encodeToString(
        """{"textures":{"SKIN":{"url":"$url"}}}""".toByteArray(),
    )
}
