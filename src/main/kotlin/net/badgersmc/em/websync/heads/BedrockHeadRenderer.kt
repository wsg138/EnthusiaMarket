package net.badgersmc.em.websync.heads

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Converts copied Geyser RGBA skin bytes into a finished public 96x96 head. */
object BedrockHeadRenderer {
    const val OUTPUT_SIZE = 96
    const val MAX_PNG_BYTES = 128 * 1024

    fun render(skin: ByteArray): ByteArray {
        val (width, height) = dimensions(skin.size)
        val scale = width / 64
        require(height >= 32 * scale) { "unsupported_skin_dimensions" }
        val composed = compose(skin, width, scale)
        val image = scale(composed, 8 * scale)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "png_writer_unavailable" }
            output.toByteArray().also { require(it.size <= MAX_PNG_BYTES) { "head_png_limit" } }
        }
    }

    fun render(skin: BufferedImage): ByteArray {
        require(skin.width == 64 && skin.height in setOf(32, 64) || skin.width == skin.height && skin.width in setOf(128, 256)) {
            "unsupported_skin_dimensions"
        }
        val rgba = ByteArray(skin.width * skin.height * 4)
        var offset = 0
        for (y in 0 until skin.height) for (x in 0 until skin.width) {
            val argb = skin.getRGB(x, y)
            rgba[offset++] = (argb ushr 16).toByte()
            rgba[offset++] = (argb ushr 8).toByte()
            rgba[offset++] = argb.toByte()
            rgba[offset++] = (argb ushr 24).toByte()
        }
        return render(rgba)
    }

    private fun compose(skin: ByteArray, width: Int, scale: Int): IntArray {
        val faceSize = 8 * scale
        val composed = IntArray(faceSize * faceSize)
        for (y in 0 until faceSize) {
            for (x in 0 until faceSize) {
                val base = pixel(skin, width, 8 * scale + x, 8 * scale + y)
                val outer = pixel(skin, width, 40 * scale + x, 8 * scale + y)
                composed[y * faceSize + x] = sourceOver(base, outer)
            }
        }
        return composed
    }

    private fun scale(composed: IntArray, faceSize: Int): BufferedImage {
        val image = BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until OUTPUT_SIZE) {
            for (x in 0 until OUTPUT_SIZE) {
                image.setRGB(x, y, composed[(y * faceSize / OUTPUT_SIZE) * faceSize + x * faceSize / OUTPUT_SIZE])
            }
        }
        return image
    }

    private fun dimensions(bytes: Int): Pair<Int, Int> = when (bytes) {
        64 * 32 * 4 -> 64 to 32
        64 * 64 * 4 -> 64 to 64
        128 * 128 * 4 -> 128 to 128
        256 * 256 * 4 -> 256 to 256
        else -> throw IllegalArgumentException("unsupported_skin_dimensions")
    }

    private fun pixel(bytes: ByteArray, width: Int, x: Int, y: Int): Int {
        val offset = (y * width + x) * 4
        val red = bytes[offset].toInt() and 0xff
        val green = bytes[offset + 1].toInt() and 0xff
        val blue = bytes[offset + 2].toInt() and 0xff
        val alpha = bytes[offset + 3].toInt() and 0xff
        return alpha shl 24 or (red shl 16) or (green shl 8) or blue
    }

    private fun sourceOver(base: Int, outer: Int): Int {
        val oa = outer ushr 24
        if (oa == 0) return base
        if (oa == 255) return outer
        val ba = base ushr 24
        val inverse = 255 - oa
        val alphaNumerator = oa * 255 + ba * inverse
        if (alphaNumerator == 0) return 0
        fun channel(shift: Int): Int {
            val foreground = outer ushr shift and 0xff
            val background = base ushr shift and 0xff
            return (foreground * oa * 255 + background * ba * inverse + alphaNumerator / 2) / alphaNumerator
        }
        val alpha = (alphaNumerator + 127) / 255
        return alpha shl 24 or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
