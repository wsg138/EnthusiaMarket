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

    /** Complete input for [build], grouped to keep callers explicit about shop boundaries. */
    data class BuildRequest(
        val identity: ShopIdentity,
        val sign: BlockPosition,
        val container: BlockPosition,
        val sale: Sale,
        val pricing: Pricing,
        val direction: SignDirection,
        val searchEnabled: Boolean = true,
    )

    data class ShopIdentity(val stallId: String, val owner: UUID)

    data class BlockPosition(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
    )

    data class Sale(val itemBase64: String, val amount: Int)

    data class Pricing(
        val price: Long,
        val costItemBase64: String? = null,
        val costAmountOverride: Int? = null,
    )

    fun build(request: BuildRequest): Shop = Shop(
        stallId = request.identity.stallId,
        owner = request.identity.owner,
        signWorld = request.sign.world,
        signX = request.sign.x,
        signY = request.sign.y,
        signZ = request.sign.z,
        containerWorld = request.container.world,
        containerX = request.container.x,
        containerY = request.container.y,
        containerZ = request.container.z,
        sellItem = request.sale.itemBase64,
        sellAmount = request.sale.amount,
        costItem = request.pricing.costItemBase64 ?: ItemStackSerializer.serialize(ItemStack(Material.RAW_GOLD, 1)),
        costAmount = request.pricing.costAmountOverride
            ?: request.pricing.price.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
        direction = request.direction,
        searchEnabled = request.searchEnabled,
    )
}
