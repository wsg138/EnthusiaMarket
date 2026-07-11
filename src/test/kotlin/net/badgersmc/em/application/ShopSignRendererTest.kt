package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.SignDirection
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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

    @Test fun `custom display name shown on sign line 1`() {
        val customName = Component.text("Legendary Sword", NamedTextColor.GOLD)
        val lines = r.lines(SignDirection.SELL, "diamond_sword", 1, "500", displayName = customName)
        // "Legendary Sword" is 15 chars → truncated to 14 + "…"
        assertEquals("1x Legendary Swor…", plain.serialize(lines[1]))
        val childColor = lines[1].children().firstOrNull()?.color()
        assertEquals(NamedTextColor.GOLD, childColor)
    }

    @Test fun `long custom name truncated with ellipsis preserving color`() {
        val customName = Component.text("Super Long Epic Diamond Sword of Doom", NamedTextColor.RED)
        val lines = r.lines(SignDirection.SELL, "diamond_sword", 1, "500", displayName = customName)
        assertEquals("1x Super Long Epi…", plain.serialize(lines[1]))
        val childColor = lines[1].children().firstOrNull()?.color()
        assertEquals(NamedTextColor.RED, childColor)
    }

    @Test fun `no custom name falls back to material name`() {
        val lines = r.lines(SignDirection.SELL, "diamond", 5, "100")
        assertEquals("5x diamond", plain.serialize(lines[1]))
    }

    @Test fun `long material name truncated`() {
        val lines = r.lines(SignDirection.SELL, "netherite_ingot", 1, "1000")
        assertEquals("1x netherite_ingo…", plain.serialize(lines[1]))
    }
}
