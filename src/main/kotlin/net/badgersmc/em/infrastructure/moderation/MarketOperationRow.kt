package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketOperationRecord
import net.enthusia.market.api.moderation.MarketOperationRequest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Optional
import java.util.UUID

internal data class MarketOperationRow(
    val operationId: UUID,
    val targetId: UUID,
    val caseId: String,
    val stallId: String,
    val state: MarketOperationRecord.State,
    val snapshotJson: String,
    val snapshotChecksum: String,
    val currentChecksum: String?,
    val reviewDueAt: Long,
    val recoveryUntil: Long,
    val blacklistExpiresAt: Long?,
    val reviewerId: UUID?,
    val detail: String,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun matches(request: MarketOperationRequest): Boolean =
        targetId == request.targetId() &&
            caseId == request.caseId() &&
            stallId == request.stallId() &&
            reviewDueAt == request.reviewDueAt().toEpochMilli() &&
            recoveryUntil == request.recoveryUntil().toEpochMilli() &&
            blacklistExpiresAt == request.blacklistExpiresAt().map(Instant::toEpochMilli).orElse(null)

    fun toRecord(): MarketOperationRecord = MarketOperationRecord(
        operationId,
        targetId,
        caseId,
        stallId,
        state,
        snapshotChecksum,
        Optional.ofNullable(currentChecksum),
        Optional.ofNullable(reviewerId),
        Instant.ofEpochMilli(reviewDueAt),
        Instant.ofEpochMilli(recoveryUntil),
        revision,
        detail,
        Instant.ofEpochMilli(updatedAt),
    )
}

internal fun Connection.findMarketOperation(operationId: UUID): MarketOperationRow? = prepareStatement(
    "SELECT * FROM market_moderation_operations WHERE operation_id = ?",
).use { statement ->
    statement.setString(1, operationId.toString())
    statement.executeQuery().use { result ->
        if (result.next()) result.toMarketOperationRow() else null
    }
}

internal fun ResultSet.toMarketOperationRow(): MarketOperationRow = MarketOperationRow(
    operationId = UUID.fromString(getString("operation_id")),
    targetId = UUID.fromString(getString("target_uuid")),
    caseId = getString("case_id"),
    stallId = getString("stall_id"),
    state = MarketOperationRecord.State.valueOf(getString("state")),
    snapshotJson = getString("snapshot_json"),
    snapshotChecksum = getString("snapshot_checksum"),
    currentChecksum = getString("current_checksum"),
    reviewDueAt = getLong("review_due_at"),
    recoveryUntil = getLong("recovery_until"),
    blacklistExpiresAt = getLong("blacklist_expires_at").takeUnless { wasNull() },
    reviewerId = getString("reviewer_uuid")?.let(UUID::fromString),
    detail = getString("detail"),
    revision = getLong("revision"),
    createdAt = getLong("created_at"),
    updatedAt = getLong("updated_at"),
)
