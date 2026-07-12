package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopSignRenderer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import org.bukkit.block.Sign

/** Shared sign-rendering logic for [ShopCommands] and [AdminCommands]. */
internal object SignRenderHelper {

    fun renderToSign(signer: ShopSignRenderer, sign: Sign, shop: Shop) {
        val deserialized = ItemStackSerializer.deserialize(shop.sellItem)
        val sell = deserialized?.type?.name?.lowercase() ?: "?"
        val displayName = deserialized?.itemMeta?.displayName()
        val costDisplay = if (shop.direction == SignDirection.TRADE) {
            val costMat = ItemStackSerializer.deserialize(shop.costItem)?.type?.name?.lowercase() ?: "?"
            "${shop.costAmount}x $costMat"
        } else {
            "${shop.costAmount}"
        }
        val side = sign.getSide(org.bukkit.block.sign.Side.FRONT)
        signer.lines(shop.direction, sell, shop.sellAmount, costDisplay, displayName)
            .forEachIndexed { i, c -> side.line(i, c) }
        sign.update()
    }
}
