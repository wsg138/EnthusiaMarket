package net.badgersmc.em.domain.stall

enum class StallState {
    UNOWNED,
    AUCTIONING,
    OWNED,
    GRACE,
    MODERATION_HOLD,
    RE_AUCTIONING,
    EMERGENCY_AUCTIONING
}
