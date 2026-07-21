package net.badgersmc.em.websync.heads

import com.google.gson.JsonParser
import java.net.URI
import java.util.Base64

/** Extracts the exact Mojang texture hash from a bounded Floodgate textures property. */
data class MojangTexture private constructor(val hash: String) {
    companion object {
        private val validHash = Regex("^[0-9a-f]{64}$")
        fun fromHash(hash: String): MojangTexture? = MojangTexture(hash).takeIf { validHash.matches(hash) }
    }
}

object FloodgateTexturePropertyParser {
    const val MAX_ENCODED = 32 * 1024
    const val MAX_DECODED = 16 * 1024
    const val MAX_SIGNATURE = 4096
    private val texturePath = Regex("^/texture/([0-9a-f]{64})$")

    fun parse(value: String): MojangTexture? = decode(value)?.let(::skinUrl)?.let(::textureHash)?.let(MojangTexture::fromHash)

    /** Validates the skin URL exposed by Paper's player profile after Floodgate has applied it. */
    fun parseUrl(value: String): MojangTexture? = textureHash(value)?.let(MojangTexture::fromHash)

    private fun decode(value: String): String? {
        if (value.length !in 1..MAX_ENCODED) return null
        val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
        if (decoded.size !in 1..MAX_DECODED || !withinJsonDepth(decoded, 16)) return null
        return decoded.toString(Charsets.UTF_8)
    }

    private fun skinUrl(json: String): String? = runCatching { JsonParser.parseString(json) }
        .getOrNull()?.asJsonObject?.getAsJsonObject("textures")?.getAsJsonObject("SKIN")?.get("url")?.asString

    private fun textureHash(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!isMojangTexture(uri)) return null
        return texturePath.matchEntire(uri.path)?.groupValues?.get(1)
    }

    private fun isMojangTexture(uri: URI): Boolean =
        uri.scheme in setOf("http", "https") && uri.host == "textures.minecraft.net" && uri.userInfo == null &&
            uri.port == -1 && uri.query == null && uri.fragment == null && uri.rawPath == uri.path

    private fun withinJsonDepth(bytes: ByteArray, maximum: Int): Boolean {
        val tracker = JsonDepthTracker(maximum)
        return bytes.all { tracker.accept(it.toInt().toChar()) } && tracker.complete()
    }

    private class JsonDepthTracker(private val maximum: Int) {
        private var depth = 0
        private var quoted = false
        private var escaped = false

        fun accept(character: Char): Boolean {
            if (quoted) return consumeQuoted(character)
            if (character == '"') quoted = true
            else if (character in "[{") depth++
            else if (character in "]}") depth--
            return depth in 0..maximum
        }

        fun complete(): Boolean = !quoted && depth == 0

        private fun consumeQuoted(character: Char): Boolean {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> quoted = false
            }
            return true
        }
    }
}
