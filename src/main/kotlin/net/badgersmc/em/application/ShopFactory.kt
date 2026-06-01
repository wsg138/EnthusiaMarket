package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Pure builder for [Shop] from menu/form inputs (REQ-012). Centralises the
 * field mapping shared by CreateShopMenu (Java) and BedrockCreateShopForm so
 * both paths produce identical, correct base64-serialised shops. Mirrors the
 * mapping in SignPlaceListener: sellItem is a base64 ItemStack, costItem is an
 * EMERALD UI hint, real price flows through costAmount (Vault).
 */
object ShopFactory {

    @Suppress("LongParameterList")
    fun build(
        stallId: String,
        owner: UUID,
        creator: UUID,
        signWorld: String, signX: Int, signY: Int, signZ: Int,
        containerWorld: String, containerX: Int, containerY: Int, containerZ: Int,
        sellItemBase64: String,
        sellAmount: Int,
        price: Long,
        direction: SignDirection,
    ): Shop = Shop(
        stallId = stallId,
        owner = owner,
        signWorld = signWorld, signX = signX, signY = signY, signZ = signZ,
        containerWorld = containerWorld, containerX = containerX, containerY = containerY, containerZ = containerZ,
        sellItem = sellItemBase64,
        sellAmount = sellAmount,
        costItem = ItemStackSerializer.serialize(ItemStack(Material.EMERALD, 1)),
        costAmount = price.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
        creatorId = creator,
        direction = direction,
    )
}