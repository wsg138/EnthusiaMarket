package net.badgersmc.em.domain.shop

import java.util.UUID

/** A completed shop trade, persisted for history + owner notifications (ItemShops parity SP6). */
data class ShopTransaction(
    val id: Long = 0,
    val shopId: Long,
    val owner: UUID,
    val buyer: UUID,
    val direction: SignDirection,
    val item: String,        // material name (lowercase)
    val quantity: Int,
    val totalPrice: Long,
    val createdAt: Long,     // epoch millis
    val notified: Boolean = false,
)
