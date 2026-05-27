package net.badgersmc.em.domain.offer

import net.badgersmc.em.domain.stall.StallId
import java.time.Instant
import java.util.UUID

/**
 * Public offer to transfer ownership of a stall to any buyer who pays
 * the listed [price] plus the configured tax. One open offer per stall
 * at a time; mutually exclusive with an open auction (REQ-263).
 */
data class SellOffer(
    val stallId: StallId,
    val sellerUuid: UUID,
    val price: Long,
    val createdAt: Instant,
) {
    init {
        require(price > 0) { "Sell offer price must be positive" }
    }
}
