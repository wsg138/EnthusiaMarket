package net.badgersmc.em.websync.heads

import com.google.gson.JsonParser
import java.net.URI
import java.util.Base64

/** Extracts the exact Mojang texture hash from a bounded Floodgate textures property. */
object FloodgateTexturePropertyParser {
    const val MAX_ENCODED = 32 * 1024
    const val MAX_DECODED = 16 * 1024
    const val MAX_SIGNATURE = 4096
    private val texturePath = Regex("^/texture/([0-9a-f]{64})$")

    fun parse(value: String): String? = decode(value)?.let(::skinUrl)?.let(::textureHash)

    private fun decode(value: String): String? {
        if (value.length !in 1..MAX_ENCODED) return null
        val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
        if (decoded.size !in 1..MAX_DECODED || !withinJsonDepth(decoded, 16)) return null
        return decoded.toString(Charsets.UTF_8)
    }

    private fun skinUrl(json: String): String? = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").asString
    }.getOrNull()

    private fun textureHash(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!isMojangTexture(uri)) return null
        return texturePath.matchEntire(uri.path)?.groupValues?.get(1)
    }

    private fun isMojangTexture(uri: URI): Boolean =
        uri.scheme in setOf("http", "https") && uri.host == "textures.minecraft.net" && uri.userInfo == null &&
            uri.port == -1 && uri.query == null && uri.fragment == null && uri.rawPath == uri.path

    private fun withinJsonDepth(bytes: ByteArray, maximum: Int): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (byte in bytes) {
            when {
                quoted -> when {
                    escaped -> escaped = false
                    byte.toInt().toChar() == '\\' -> escaped = true
                    byte.toInt().toChar() == '"' -> quoted = false
                }
                byte.toInt().toChar() == '"' -> quoted = true
                byte.toInt().toChar() in "[{" -> if (++depth > maximum) return false
                byte.toInt().toChar() in "]}" -> if (--depth < 0) return false
            }
        }
        return !quoted && depth == 0
    }
}
