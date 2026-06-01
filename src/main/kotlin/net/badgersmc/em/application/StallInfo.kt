package net.badgersmc.em.application

import net.badgersmc.em.domain.stall.StallState
import java.time.Instant

/**
 * Structured region info card data (REQ-230). All nine player-facing fields
 * are computed in the application layer so the infra renderer only formats.
 */
data class StallInfo(
    val stallId: String,
    val kind: String,
    val ownerName: String,
    val memberCount: Int,
    val currentRent: Long,
    val nextRentAt: Instant?,
    val width: Int,
    val height: Int,
    val length: Int,
    val state: StallState,
    val available: Boolean,
)