package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.SignDirection
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopSignRendererTest {

    private val plain = PlainTextComponentSerializer.plainText()
    private val r = ShopSignRenderer()

    @Test fun `sell sign renders the four lines`() {
        val lines = r.lines(SignDirection.SELL, "diamond", 5, "100")
        assertEquals("[SELL]", plain.serialize(lines[0]))
        assertEquals("5x diamond", plain.serialize(lines[1]))
        assertEquals("100", plain.serialize(lines[2]))
        assertEquals("[Shop]", plain.serialize(lines[3]))
    }

    @Test fun `buy sign uses the BUY header`() {
        assertEquals("[BUY]", plain.serialize(r.lines(SignDirection.BUY, "iron_ingot", 1, "5")[0]))
    }

    @Test fun `trade sign renders cost display`() {
        val lines = r.lines(SignDirection.TRADE, "diamond", 2, "16x emerald")
        assertEquals("[TRADE]", plain.serialize(lines[0]))
        assertEquals("2x diamond", plain.serialize(lines[1]))
        assertEquals("16x emerald", plain.serialize(lines[2]))
        assertEquals("[Shop]", plain.serialize(lines[3]))
    }
}
