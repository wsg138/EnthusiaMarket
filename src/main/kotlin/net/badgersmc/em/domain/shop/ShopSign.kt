package net.badgersmc.em.domain.shop

import net.badgersmc.em.domain.stall.StallId

data class ShopSign(
    val id: Long,
    val stallId: StallId,
    val direction: SignDirection,
    val itemKey: String,
    val price: Long,
    val signLocation: String,
    val containerLocation: String
) {
    init {
        require(price > 0) { "Sign price must be positive" }
        require(itemKey.isNotBlank()) { "Item key must not be blank" }
    }
}
