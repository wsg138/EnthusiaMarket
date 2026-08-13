package net.badgersmc.em.domain.ports

/** Fast local projection of durable stall moderation reservations. */
interface MarketMutationGate {
    fun isStallLocked(stallId: String): Boolean

    object Open : MarketMutationGate {
        override fun isStallLocked(stallId: String): Boolean = false
    }
}
