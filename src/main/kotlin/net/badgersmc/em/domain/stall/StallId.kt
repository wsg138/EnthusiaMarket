package net.badgersmc.em.domain.stall

@JvmInline
value class StallId(val value: String) {
    init { require(value.isNotBlank()) { "StallId must not be blank" } }
    override fun toString(): String = value
}
