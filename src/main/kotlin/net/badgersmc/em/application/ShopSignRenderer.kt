package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.annotations.Service
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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
            truncate(displayName)
        } else {
            // Truncate material name too — e.g. "netherite_ingot" (15 chars) → "netherite_ing…"
            val truncated = if (sellMaterialName.length > 14) sellMaterialName.take(14) + "…" else sellMaterialName
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
    private fun truncate(display: Component, maxLen: Int = 14): Component {
        val plain = PlainTextComponentSerializer.plainText().serialize(display)
        if (plain.length <= maxLen) return display
        return Component.text(plain.take(maxLen) + "…", display.style())
    }
}
