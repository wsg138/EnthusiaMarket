package net.badgersmc.em.infrastructure.moderation

import java.util.UUID

internal data class BlacklistWrite(
    val operationId: UUID,
    val playerId: UUID,
    val caseId: String,
    val expiresAt: Long?,
    val revision: Long,
    val updatedAt: Long,
)
