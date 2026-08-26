package net.badgersmc.em.infrastructure.persistence

/** Raised before a stale or moderation-reserved stall write can change ownership state. */
class MarketModerationConflictException(message: String) : IllegalStateException(message)
