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
        // "Legendary Sword" is 15 chars → truncated to 11 + "…"
        assertEquals("1x Legendary S…", plain.serialize(lines[1]))
        val childColor = lines[1].children().firstOrNull()?.color()
        assertEquals(NamedTextColor.GOLD, childColor)
    }

    @Test fun `long custom name truncated with ellipsis preserving color`() {
        val customName = Component.text("Super Long Epic Diamond Sword of Doom", NamedTextColor.RED)
        val lines = r.lines(SignDirection.SELL, "diamond_sword", 1, "500", displayName = customName)
        assertEquals("1x Super Long …", plain.serialize(lines[1]))
        val childColor = lines[1].children().firstOrNull()?.color()
        assertEquals(NamedTextColor.RED, childColor)
    }

    @Test fun `no custom name falls back to material name`() {
        val lines = r.lines(SignDirection.SELL, "diamond", 5, "100")
        assertEquals("5x diamond", plain.serialize(lines[1]))
    }

    @Test fun `long material name truncated`() {
        val lines = r.lines(SignDirection.SELL, "netherite_ingot", 1, "1000")
        assertEquals("1x netherite_i…", plain.serialize(lines[1]))
    }

    @Test fun `MiniMessage gradient display name preserved on sign`() {
        val gradientName = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
            .deserialize("<gradient:red:gold>Epic Sword</gradient>")
        val lines = r.lines(SignDirection.SELL, "diamond_sword", 1, "500", displayName = gradientName)
        assertEquals("1x Epic Sword", plain.serialize(lines[1]))
        // Gradient display name must not be flattened to plain text — verify
        // the appended component has children (gradient produces per-char styling)
        val itemChild = lines[1].children()
        assert(itemChild.isNotEmpty()) { "gradient display name was flattened — no children found" }
    }

    @Test fun `unparsed MiniMessage display name parsed on sign`() {
        // Simulate a plugin storing MiniMessage as literal text (common with Nexo/ItemsAdder)
        val raw = Component.text("<b><color:#FF0000>† GOD †</color></b>")
        val lines = r.lines(SignDirection.SELL, "diamond_helmet", 1, "120", displayName = raw)
        // Must NOT show raw markup — must show parsed text without tags
        val line2 = plain.serialize(lines[1])
        assertEquals("1x † GOD †", line2)
        // Must have children (MiniMessage was parsed into a styled component tree)
        assert(lines[1].children().isNotEmpty()) { "MiniMessage not parsed — no children" }
        // Any descendant should have bold, red, or non-default styling
        val allDescendants = collectDescendants(lines[1])
        val anyStyled = allDescendants.any {
            it.color() != null ||
                it.style().hasDecoration(net.kyori.adventure.text.format.TextDecoration.BOLD) ||
                it.style().hasDecoration(net.kyori.adventure.text.format.TextDecoration.ITALIC)
        }
        assert(anyStyled) { "MiniMessage styling not applied — all descendants have default style" }
    }

    /** Recurse into children to collect all nested components. */
    private fun collectDescendants(component: net.kyori.adventure.text.Component): List<net.kyori.adventure.text.Component> {
        return component.children() + component.children().flatMap { collectDescendants(it) }
    }

    @Test fun `plain display name without MiniMessage not re-parsed`() {
        val plainName = Component.text("† GOD †")
        val lines = r.lines(SignDirection.SELL, "diamond_helmet", 1, "120", displayName = plainName)
        assertEquals("1x † GOD †", plain.serialize(lines[1]))
        // Should remain as-is — not get re-parsed (no MiniMessage tags detected)
    }
}
