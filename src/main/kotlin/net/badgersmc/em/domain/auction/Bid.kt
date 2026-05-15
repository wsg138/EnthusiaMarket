package net.badgersmc.em.domain.auction

import java.time.Instant
import java.util.UUID

data class Bid(
    val bidder: UUID,
    val amount: Long,
    val placedAt: Instant
) {
    init { require(amount > 0) { "Bid amount must be positive" } }
}
