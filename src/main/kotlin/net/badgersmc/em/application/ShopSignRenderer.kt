package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.annotations.Service
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/**
 * Pure renderer for the four shop-sign lines (ItemShops parity). Extracted from
 * SignPlaceListener so shop creation and `/shop admin fix` produce identical signs.
 * Bukkit-free: the caller passes the already-resolved sell-item material name.
 */
@Service
class ShopSignRenderer {

    /** [SELL]/[BUY] header · `Nx material` · price · [Shop]. */
    fun lines(direction: SignDirection, sellMaterialName: String, sellAmount: Int, price: Long): List<Component> {
        val headerColor = when (direction) {
            SignDirection.BUY -> NamedTextColor.GOLD
            SignDirection.TRADE -> NamedTextColor.LIGHT_PURPLE
            else -> NamedTextColor.AQUA
        }
        return listOf(
            Component.text("[${direction.name}]", headerColor),
            Component.text("${sellAmount}x $sellMaterialName", NamedTextColor.WHITE),
            Component.text("$price", NamedTextColor.GOLD),
            Component.text("[Shop]", NamedTextColor.GOLD),
        )
    }
}
