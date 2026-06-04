package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.SignDirection
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopSignRendererTest {

    private val plain = PlainTextComponentSerializer.plainText()
    private val r = ShopSignRenderer()

    @Test fun `sell sign renders the four lines`() {
        val lines = r.lines(SignDirection.SELL, "diamond", 5, 100L)
        assertEquals("[SELL]", plain.serialize(lines[0]))
        assertEquals("5x diamond", plain.serialize(lines[1]))
        assertEquals("100", plain.serialize(lines[2]))
        assertEquals("[Shop]", plain.serialize(lines[3]))
    }

    @Test fun `buy sign uses the BUY header`() {
        assertEquals("[BUY]", plain.serialize(r.lines(SignDirection.BUY, "iron_ingot", 1, 5L)[0]))
    }
}
