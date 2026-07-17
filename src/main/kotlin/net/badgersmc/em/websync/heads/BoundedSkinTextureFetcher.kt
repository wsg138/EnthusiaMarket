package net.badgersmc.em.websync.heads

import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.imageio.ImageIO

/** Fetches a bounded Mojang skin texture identified by its validated texture hash. */
fun interface SkinTextureFetcher {
    @Throws(IOException::class, IllegalArgumentException::class)
    fun fetch(texture: MojangTexture): BufferedImage
}

object DefaultSkinTextureFetcher : SkinTextureFetcher {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val HOST = "textures.minecraft.net"
    override fun fetch(texture: MojangTexture): BufferedImage {
        return connection(texture).useConnection { decodeSkin(readPng(it)) }
    }

    private fun connection(texture: MojangTexture): HttpURLConnection =
        (URL("https", HOST, "/texture/${texture.hash}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 7_000
            setRequestProperty("Accept", "image/png")
        }

    private fun readPng(connection: HttpURLConnection): ByteArray {
        if (connection.responseCode !in 200..299) throw IOException("skin_http_status")
        return BufferedInputStream(connection.inputStream).use { input ->
            input.readNBytes(MAX_BYTES + 1).also { require(it.size <= MAX_BYTES) { "skin_size_limit" } }
        }
    }

    private fun decodeSkin(bytes: ByteArray): BufferedImage {
        requirePngHeader(bytes)
        val width = readInt(bytes, 16)
        val height = readInt(bytes, 20)
        requireSupportedDimensions(width, height)
        return ImageIO.read(bytes.inputStream())?.also { require(it.width == width && it.height == height) { "invalid_png" } }
            ?: throw IllegalArgumentException("invalid_png")
    }

    private fun requirePngHeader(bytes: ByteArray) {
        require(bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "not_png" }
        require(String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR") { "invalid_png" }
    }

    private fun requireSupportedDimensions(width: Int, height: Int) {
        if (width == 64 && height in setOf(32, 64)) return
        require(width == height && width in setOf(128, 256)) { "unsupported_skin_dimensions" }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff shl 24) or (bytes[offset + 1].toInt() and 0xff shl 16) or
            (bytes[offset + 2].toInt() and 0xff shl 8) or (bytes[offset + 3].toInt() and 0xff)

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }
}
