package net.badgersmc.em.domain.ports

import java.util.UUID

/** Coordinates ordinary acquisition work with durable Staff moderation fences. */
interface MarketModerationPolicy {
    /**
     * Run an ownership-acquisition action while a short durable player fence is held.
     * Implementations must reject an active blacklist or moderation reservation.
     */
    fun <T> withAcquisitionPermit(playerId: UUID, action: () -> T): T

    /** True when a new acquisition would be accepted at the time of the check. */
    fun canAcquire(playerId: UUID): Boolean

    object AllowAll : MarketModerationPolicy {
        override fun <T> withAcquisitionPermit(playerId: UUID, action: () -> T): T = action()
        override fun canAcquire(playerId: UUID): Boolean = true
    }
}

class MarketAcquisitionBlockedException(message: String) : IllegalStateException(message)
