package net.badgersmc.em.websync.heads

import com.google.gson.JsonParser
import java.net.URI
import java.util.Base64

object FloodgateTexturePropertyParser {
    const val MAX_ENCODED = 32 * 1024
    const val MAX_DECODED = 16 * 1024
    const val MAX_SIGNATURE = 4096
    private val path = Regex("^/texture/([0-9a-f]{64})$")

    fun parse(value: String): String? {
        if (value.length !in 1..MAX_ENCODED) return null
        val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
        if (decoded.size !in 1..MAX_DECODED) return null
        if (!withinJsonDepth(decoded, 16)) return null
        val url = runCatching { JsonParser.parseString(decoded.toString(Charsets.UTF_8))
            .asJsonObject.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").asString }.getOrNull() ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host != "textures.minecraft.net" || uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null || uri.rawPath != uri.path) return null
        val match = path.matchEntire(uri.path) ?: return null
        return "https://textures.minecraft.net/texture/${match.groupValues[1]}"
    }

    private fun withinJsonDepth(bytes: ByteArray, maximum: Int): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (byte in bytes) {
            val character = byte.toInt().toChar()
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
                continue
            }
            when (character) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > maximum) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return !quoted && depth == 0
    }
}
