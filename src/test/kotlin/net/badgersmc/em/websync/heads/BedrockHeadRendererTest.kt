@file:Suppress("FunctionNaming", "MagicNumber")

package net.badgersmc.em.websync.heads

import java.awt.Rectangle
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BedrockHeadRendererTest {
    @Test
    fun `transparent outer layer preserves base face`() {
        val skin = skin(64, 64)
        fill(skin, 64, Rectangle(8, 8, 8, 8), argb(255, 30, 80, 140))

        val image = image(BedrockHeadRenderer.render(skin))

        assertEquals(96, image.width)
        assertEquals(96, image.height)
        assertEquals(argb(255, 30, 80, 140), image.getRGB(48, 48))
    }

    @Test
    fun `opaque outer layer replaces the base face`() {
        val opaque = skin(64, 64)
        fill(opaque, 64, Rectangle(8, 8, 8, 8), argb(255, 255, 0, 0))
        fill(opaque, 64, Rectangle(40, 8, 8, 8), argb(255, 0, 0, 255))
        assertEquals(argb(255, 0, 0, 255), image(BedrockHeadRenderer.render(opaque)).getRGB(10, 10))
    }

    @Test
    fun `translucent outer layer uses source-over composition`() {
        val translucent = skin(64, 64)
        fill(translucent, 64, Rectangle(8, 8, 8, 8), argb(255, 255, 0, 0))
        fill(translucent, 64, Rectangle(40, 8, 8, 8), argb(128, 0, 0, 255))
        val color = image(BedrockHeadRenderer.render(translucent)).getRGB(10, 10)
        assertEquals(255, color ushr 24)
        assertTrue((color ushr 16 and 0xff) in 126..127)
        assertEquals(128, color and 0xff)
    }

    @Test
    fun `legacy and high-resolution skins retain their face detail`() {
        listOf(64 to 32, 128 to 128, 256 to 256).forEach { (width, height) -> assertFaceDetail(width, height) }
    }

    private fun assertFaceDetail(width: Int, height: Int) {
        val scale = width / 64
        val skin = skin(width, height)
        fill(skin, width, Rectangle(8 * scale, 8 * scale, 8 * scale, 8 * scale), argb(255, 20, 40, 60))
        fill(skin, width, Rectangle(8 * scale, 8 * scale, scale, scale), argb(255, 240, 30, 10))
        val image = image(BedrockHeadRenderer.render(skin))
        assertEquals(argb(255, 240, 30, 10), image.getRGB(1, 1))
        assertEquals(argb(255, 20, 40, 60), image.getRGB(95, 95))
    }

    @Test
    fun `output is deterministic bounded RGBA PNG and invalid dimensions fail`() {
        val skin = skin(64, 64)
        fill(skin, 64, Rectangle(8, 8, 8, 8), argb(255, 1, 2, 3))
        val first = BedrockHeadRenderer.render(skin)
        val second = BedrockHeadRenderer.render(skin)
        assertTrue(first.contentEquals(second))
        assertTrue(first.size <= BedrockHeadRenderer.MAX_PNG_BYTES)
        assertEquals(4, image(first).colorModel.numComponents)
        assertFailsWith<IllegalArgumentException> { BedrockHeadRenderer.render(ByteArray(17)) }
    }

    @Test
    fun `buffered PNG skin is rendered without retaining its source format`() {
        val source = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        source.setRGB(8, 8, argb(255, 4, 5, 6))
        val output = image(BedrockHeadRenderer.render(source))
        assertEquals(argb(255, 4, 5, 6), output.getRGB(1, 1))
    }

    private fun skin(width: Int, height: Int) = ByteArray(width * height * 4)

    private fun fill(bytes: ByteArray, width: Int, area: Rectangle, argb: Int) {
        for (y in area.y until area.y + area.height) for (x in area.x until area.x + area.width) {
            val offset = (y * width + x) * 4
            bytes[offset] = (argb ushr 16).toByte()
            bytes[offset + 1] = (argb ushr 8).toByte()
            bytes[offset + 2] = argb.toByte()
            bytes[offset + 3] = (argb ushr 24).toByte()
        }
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int) =
        alpha shl 24 or (red shl 16) or (green shl 8) or blue

    private fun image(bytes: ByteArray) = ImageIO.read(ByteArrayInputStream(bytes))
}
