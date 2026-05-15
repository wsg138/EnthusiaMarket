package net.badgersmc.em.domain.auction

@JvmInline
value class AuctionId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}
