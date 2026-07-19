package net.badgersmc.em.interaction.gui

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection

/** Shared display helpers for shop menus — avoids duplicating direction/stock formatting. */
object ShopDisplay {

    fun directionLabel(direction: SignDirection): String = when (direction) {
        SignDirection.SELL -> "<green>Sell</green>"
        SignDirection.BUY -> "<gold>Buy</gold>"
        SignDirection.TRADE -> "<light_purple>Trade</light_purple>"
    }

    fun tradesAvailable(shop: Shop): Int = when (shop.direction) {
        SignDirection.BUY -> Int.MAX_VALUE // BUY capacity and affordability are service-owned checks
        else -> if (shop.adminShop) Int.MAX_VALUE else if (shop.sellAmount > 0) shop.stockCount / shop.sellAmount else 0
    }
}
