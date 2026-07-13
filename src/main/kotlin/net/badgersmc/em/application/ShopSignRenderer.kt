package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.annotations.Service
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * Pure renderer for the four shop-sign lines (ItemShops parity). Extracted from
 * SignPlaceListener so shop creation and `/shop admin fix` produce identical signs.
 * Bukkit-free: the caller passes the already-resolved sell-item material name.
 */
@Service
class ShopSignRenderer {

    /** [SELL]/[BUY]/[TRADE] header · `Nx material` · cost display · [Shop]. */
    fun lines(
        direction: SignDirection,
        sellMaterialName: String,
        sellAmount: Int,
        costDisplay: String,
        displayName: Component? = null,
    ): List<Component> {
        val itemName: Component = if (displayName != null) {
            truncate(parseMiniMessageIfNeeded(displayName))
        } else {
            // Truncate material name — "totem_of_undying" → "totem_of_un…" (fits after "1x " prefix)
            val truncated = if (sellMaterialName.length > 11) sellMaterialName.take(11) + "…" else sellMaterialName
            Component.text(truncated, NamedTextColor.WHITE)
        }
        val headerColor = when (direction) {
            SignDirection.BUY -> NamedTextColor.GOLD
            SignDirection.TRADE -> NamedTextColor.LIGHT_PURPLE
            else -> NamedTextColor.AQUA
        }
        return listOf(
            Component.text("[${direction.name}]", headerColor),
            Component.text("${sellAmount}x ", NamedTextColor.WHITE).append(itemName),
            Component.text(costDisplay, NamedTextColor.GOLD),
            Component.text("[Shop]", NamedTextColor.GOLD),
        )
    }

    /**
     * Truncate the plain-text representation of [display] to [maxLen] characters followed
     * by an ellipsis ("…"). The original [Component.style] is preserved on the truncated
     * result.
     *
     * **Limitation:** truncation serializes the component to plain text via
     * [PlainTextComponentSerializer] and reapplies only the root component's style.
     * Styling from child components (e.g. per-word colors in a
     * `Component.text("Red").append(Component.text("Blue", BLUE))` chain) is lost.
     * Callers with rich nested styling should truncate at a higher level before
     * constructing the multi-style component.
     */
    private fun truncate(display: Component, maxLen: Int = 11): Component {
        val plain = PlainTextComponentSerializer.plainText().serialize(display)
        if (plain.length <= maxLen) return display
        return Component.text(plain.take(maxLen) + "…", display.style())
    }

    /**
     * Detect unparsed MiniMessage strings embedded in a plain-text display name
     * and parse them. Plugins like Nexo/ItemsAdder often set display names as
     * literal MiniMessage strings rather than pre-parsed Components. If the
     * [display] has no styled children and its plain text looks like MiniMessage
     * (contains '<' tags), attempt to deserialize it. Falls back gracefully on
     * parse failure.
     */
    private fun parseMiniMessageIfNeeded(display: Component): Component {
        val plain = PlainTextComponentSerializer.plainText().serialize(display)
        // Must look like MiniMessage AND not already be a styled component
        val looksLikeMini = plain.contains('<') &&
            (plain.contains("</") || plain.contains("color:") || plain.contains("gradient:"))
        if (!looksLikeMini) return display

        // If the component already has styled children (per-char colors, etc.),
        // it's already parsed — don't touch it
        if (display.children().any { hasStyledChildren(it) }) return display

        return try {
            MiniMessage.miniMessage().deserialize(plain)
        } catch (_: Exception) {
            display // Parse failed — return original
        }
    }

    private fun hasStyledChildren(component: Component): Boolean {
        val s = component.style()
        return s.color() != null ||
            s.hasDecoration(net.kyori.adventure.text.format.TextDecoration.BOLD) ||
            s.hasDecoration(net.kyori.adventure.text.format.TextDecoration.ITALIC) ||
            component.children().isNotEmpty()
    }
}
