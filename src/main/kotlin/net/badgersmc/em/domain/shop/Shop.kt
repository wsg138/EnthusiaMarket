package net.badgersmc.em.domain.shop

import java.util.UUID

/**
 * A container-linked shop sign within a stall.
 *
 * @property id Auto-generated database ID.
 * @property stallId The stall this shop belongs to.
 * @property owner The UUID of the shop owner (may differ from stall owner for trust).
 * @property signWorld The world of the sign.
 * @property signX X coordinate of the sign block.
 * @property signY Y coordinate of the sign block.
 * @property signZ Z coordinate of the sign block.
 * @property containerWorld The world of the linked container.
 * @property containerX X coordinate of the container.
 * @property containerY Y coordinate of the container.
 * @property containerZ Z coordinate of the container.
 * @property sellItem Serialized ItemStack the shop sells (base64).
 * @property sellAmount Quantity per trade.
 * @property costItem Serialized ItemStack the shop pays/receives (base64).
 * @property costAmount Quantity per trade.
 * @property trusted Set of UUIDs who can edit the shop.
 * @property hopperAllowIn Whether hoppers can insert items into the container.
 * @property hopperAllowOut Whether hoppers can extract items from the container.
 * @property frozen Whether the shop is frozen (trades blocked).
 * @property adminShop Whether this is an admin shop (unlimited stock).
 * @property guildId The Discord guild this shop belongs to (null for legacy shops).
 * @property creatorId The Discord user who created this shop (null for legacy shops).
 */
data class Shop(
    val id: Long = 0,
    val stallId: String,
    val owner: UUID,
    val signWorld: String,
    val signX: Int,
    val signY: Int,
    val signZ: Int,
    val containerWorld: String,
    val containerX: Int,
    val containerY: Int,
    val containerZ: Int,
    val sellItem: String,  // base64 serialized ItemStack
    val sellAmount: Int,
    val costItem: String,  // base64 serialized ItemStack
    val costAmount: Int,
    val trusted: Set<UUID> = emptySet(),
    val hopperAllowIn: Boolean = true,
    val hopperAllowOut: Boolean = true,
    val frozen: Boolean = false,
    val adminShop: Boolean = false,
    val guildId: UUID? = null,
    val creatorId: UUID? = null
)