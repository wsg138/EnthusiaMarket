package net.badgersmc.em.websync.heads

import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.imageio.ImageIO

/** Fetches only a small, allowlisted Mojang skin texture without following redirects. */
fun interface SkinTextureFetcher {
    @Throws(IOException::class, IllegalArgumentException::class)
    fun fetch(url: String): BufferedImage
}

object DefaultSkinTextureFetcher : SkinTextureFetcher {
    private const val MAX_BYTES = 2 * 1024 * 1024
    override fun fetch(url: String): BufferedImage {
        requireAllowedUrl(url)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 7_000
            setRequestProperty("Accept", "image/png")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("skin_http_status")
            val bytes = BufferedInputStream(connection.inputStream).use { input ->
                input.readNBytes(MAX_BYTES + 1).also { require(it.size <= MAX_BYTES) { "skin_size_limit" } }
            }
            require(bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "not_png" }
            require(String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR") { "invalid_png" }
            val width = readInt(bytes, 16)
            val height = readInt(bytes, 20)
            require(width == 64 && height in setOf(32, 64) || width == height && width in setOf(128, 256)) { "unsupported_skin_dimensions" }
            return ImageIO.read(bytes.inputStream())?.also { require(it.width == width && it.height == height) { "invalid_png" } }
                ?: throw IllegalArgumentException("invalid_png")
        } finally {
            connection.disconnect()
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff shl 24) or (bytes[offset + 1].toInt() and 0xff shl 16) or
            (bytes[offset + 2].toInt() and 0xff shl 8) or (bytes[offset + 3].toInt() and 0xff)

    private fun requireAllowedUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host == "textures.minecraft.net" && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null)
        require(Regex("^/texture/[0-9a-f]{64}$").matches(uri.path) && uri.rawPath == uri.path)
    }
}
