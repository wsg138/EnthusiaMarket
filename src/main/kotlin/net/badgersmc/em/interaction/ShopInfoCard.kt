package net.badgersmc.em.interaction

import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component

/** Pure builder for the shift-right-click shop info card (ItemShops parity SP6). */
object ShopInfoCard {
    fun lines(
        lang: LangService, direction: String, item: String, qty: Int, price: Long, owner: String, stock: Int,
    ): List<Component> = listOf(
        lang.msg("shop.info.line1", "dir" to direction),
        lang.msg("shop.info.line2", "qty" to qty, "item" to item),
        lang.msg("shop.info.line3", "price" to price),
        lang.msg("shop.info.line4", "owner" to owner, "stock" to stock),
    )
}
