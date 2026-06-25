package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.logging.Logger

/**
 * Pure builder for [Shop] from menu/form inputs (REQ-012). Centralises the
 * field mapping shared by CreateShopMenu (Java) and BedrockCreateShopForm so
 * both paths produce identical, correct base64-serialised shops. Mirrors the
 * mapping in SignPlaceListener: sellItem is a base64 ItemStack, costItem is an
 * EMERALD UI hint, real price flows through costAmount (Vault).
 */
object ShopFactory {

    private val log = Logger.getLogger(ShopFactory::class.java.name)

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
        searchEnabled: Boolean = true,
        costItemBase64: String? = null,
        costAmountOverride: Int? = null,
        guildId: String? = null,
    ): Shop = Shop(
        stallId = stallId,
        owner = owner,
        signWorld = signWorld, signX = signX, signY = signY, signZ = signZ,
        containerWorld = containerWorld, containerX = containerX, containerY = containerY, containerZ = containerZ,
        sellItem = sellItemBase64,
        sellAmount = sellAmount,
        costItem = when (direction) {
            SignDirection.TRADE ->
                requireNotNull(costItemBase64) { "TRADE shops require costItemBase64" }
            else ->
                costItemBase64 ?: ItemStackSerializer.serialize(ItemStack(Material.EMERALD, 1))
        },
        costAmount = when (direction) {
            SignDirection.TRADE ->
                requireNotNull(costAmountOverride) { "TRADE shops require costAmountOverride" }
            else ->
                price.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        },
        creatorId = creator,
        direction = direction,
        searchEnabled = searchEnabled,
        guildId = guildId?.let { id ->
            try { UUID.fromString(id) } catch (_: IllegalArgumentException) {
                log.warning("ShopFactory: guildId '$id' is not a valid UUID; shop will have no guildId")
                null
            }
        },
    )
}