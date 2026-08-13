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
 * RAW_GOLD UI hint (glinting), real price flows through costAmount (Vault).
 */
object ShopFactory {

    data class Position(val world: String, val x: Int, val y: Int, val z: Int)

    data class ItemAmount(val base64: String, val amount: Int)

    data class Cost(
        val price: Long,
        val itemBase64: String? = null,
        val amountOverride: Int? = null,
    )

    data class Input(
        val stallId: String,
        val owner: UUID,
        val sign: Position,
        val container: Position,
        val sell: ItemAmount,
        val cost: Cost,
        val direction: SignDirection,
        val searchEnabled: Boolean = true,
    )

    fun build(input: Input): Shop {
        return Shop(
            stallId = input.stallId,
            owner = input.owner,
            signWorld = input.sign.world,
            signX = input.sign.x,
            signY = input.sign.y,
            signZ = input.sign.z,
            containerWorld = input.container.world,
            containerX = input.container.x,
            containerY = input.container.y,
            containerZ = input.container.z,
            sellItem = input.sell.base64,
            sellAmount = input.sell.amount,
            costItem = input.cost.itemBase64 ?: ItemStackSerializer.serialize(ItemStack(Material.RAW_GOLD, 1)),
            costAmount = input.cost.amountOverride
                ?: input.cost.price.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
            direction = input.direction,
            searchEnabled = input.searchEnabled,
        )
    }
}
