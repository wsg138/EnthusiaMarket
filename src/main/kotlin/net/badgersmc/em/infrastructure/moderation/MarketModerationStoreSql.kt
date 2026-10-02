package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketOperationRecord
import net.enthusia.market.api.moderation.MarketOperationRequest
import net.enthusia.market.api.moderation.MarketOwnership
import net.enthusia.market.api.moderation.MarketStallRecord
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.util.Optional
import java.util.UUID

internal data class OperationUpdate(
    val state: MarketOperationRecord.State,
    val currentChecksum: String,
    val reviewerId: UUID?,
    val detail: String,
    val updatedAt: Long,
)

internal data class PreparedOperationInsert(
    val request: MarketOperationRequest,
    val original: CapturedMarketSnapshot,
    val currentChecksum: String,
    val now: Long,
)

internal fun Connection.lockMarketSnapshotRows(stallId: String) {
    touchStall(stallId)
    requireStall(stallId)
    prepareStatement("UPDATE shop_items SET stock_count = stock_count WHERE stall_id = ?").use { statement ->
        statement.setString(1, stallId)
        statement.executeUpdate()
    }
}

private fun Connection.touchStall(stallId: String) {
    prepareStatement("UPDATE stalls SET moderation_revision = moderation_revision WHERE id = ?").use { statement ->
        statement.setString(1, stallId)
        statement.executeUpdate()
    }
}

private fun Connection.requireStall(stallId: String) {
    prepareStatement("SELECT 1 FROM stalls WHERE id = ?").use { statement ->
        statement.setString(1, stallId)
        statement.executeQuery().use { result ->
            if (!result.next()) throw MarketModerationRejected("Market stall '$stallId' does not exist")
        }
    }
}

internal fun Connection.reserveMarketStall(stallId: String, operationId: UUID, acquiredAt: Long) {
    try {
        prepareStatement(
            "INSERT INTO market_moderation_locks(stall_id, operation_id, acquired_at) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setString(1, stallId)
            statement.setString(2, operationId.toString())
            statement.setLong(3, acquiredAt)
            statement.executeUpdate()
        }
    } catch (failure: SQLException) {
        if (failure.isDuplicateKeyViolation() || failure.isTransactionContention()) {
            throw MarketModerationConflict("Market stall is reserved or changed by another operation")
        }
        throw failure
    }
}

internal fun Connection.advanceMarketStallRevision(stallId: String, expectedRevision: Long) {
    prepareStatement(
        "UPDATE stalls SET moderation_revision = moderation_revision + 1 WHERE id = ? AND moderation_revision = ?",
    ).use { statement ->
        statement.setString(1, stallId)
        statement.setLong(2, expectedRevision)
        if (statement.executeUpdate() != 1) {
            throw MarketModerationConflict("Market stall changed while it was being reserved")
        }
    }
}

internal fun Connection.freezeMarketShops(stallId: String) {
    prepareStatement("UPDATE shop_items SET frozen = 1 WHERE stall_id = ?").use { statement ->
        statement.setString(1, stallId)
        statement.executeUpdate()
    }
}

internal fun Connection.insertMarketOperation(insert: PreparedOperationInsert) {
    try {
        prepareStatement(INSERT_OPERATION_SQL).use { statement ->
            statement.bindPreparedOperation(insert)
            statement.executeUpdate()
        }
    } catch (failure: SQLException) {
        if (failure.isDuplicateKeyViolation()) {
            throw MarketModerationConflict("Case already has a moderation operation for this stall")
        }
        throw failure
    }
}

private fun PreparedStatement.bindPreparedOperation(insert: PreparedOperationInsert) {
    val request = insert.request
    setString(1, request.operationId().toString())
    setString(2, request.targetId().toString())
    setString(3, request.caseId())
    setString(4, request.stallId())
    setString(5, insert.original.json)
    setString(6, insert.original.checksum)
    setString(7, insert.currentChecksum)
    setLong(8, request.reviewDueAt().toEpochMilli())
    setLong(9, request.recoveryUntil().toEpochMilli())
    setNullableLong(10, request.blacklistExpiresAt().map(Instant::toEpochMilli).orElse(null))
    setString(11, "Market stall reserved pending explicit Staff review")
    setLong(12, insert.now)
    setLong(13, insert.now)
}

internal fun Connection.holdMarketStall(operation: MarketOperationRow, expectedRevision: Long) {
    prepareStatement(HOLD_STALL_SQL).use { statement ->
        statement.setString(1, operation.stallId)
        statement.setLong(2, expectedRevision)
        if (statement.executeUpdate() != 1) {
            throw MarketModerationConflict("Market stall changed during reviewed confiscation")
        }
    }
}

internal fun Connection.restoreMarketStall(stall: ModeratedStallSnapshot, expectedRevision: Long) {
    prepareStatement(RESTORE_STALL_SQL).use { statement ->
        statement.bindRestoredStall(stall, expectedRevision)
        if (statement.executeUpdate() != 1) {
            throw MarketModerationConflict("Market stall changed during restoration")
        }
    }
}

private fun PreparedStatement.bindRestoredStall(stall: ModeratedStallSnapshot, expectedRevision: Long) {
    setString(1, stall.regionId)
    setString(2, stall.world)
    setString(3, stall.state)
    setString(4, stall.ownerType)
    setString(5, stall.ownerId)
    setNullableLong(6, stall.ownerSince)
    setLong(7, stall.winningBid)
    setString(8, stall.rentMode)
    setDouble(9, stall.rentPct)
    setLong(10, stall.rentFlat)
    setString(11, stall.members.joinToString(","))
    setInt(12, stall.maxMembers)
    setNullableLong(13, stall.nextRentAt)
    setString(14, stall.kind)
    setString(15, stall.extraEntities.entries.joinToString(",") { "${it.key}:${it.value}" })
    setInt(16, stall.extraTotal)
    setString(17, stall.id)
    setLong(18, expectedRevision)
}

internal fun Connection.restoreMarketShopFlags(shops: List<ModeratedShopSnapshot>) {
    prepareStatement("UPDATE shop_items SET frozen = ? WHERE id = ? AND stall_id = ?").use { statement ->
        shops.forEach { shop ->
            statement.setBoolean(1, shop.frozen)
            statement.setLong(2, shop.id)
            statement.setString(3, shop.stallId)
            if (statement.executeUpdate() != 1) {
                throw MarketModerationConflict("A market shop disappeared during restoration")
            }
        }
    }
}

internal fun Connection.updateMarketOperation(
    operation: MarketOperationRow,
    update: OperationUpdate,
): MarketOperationRow {
    prepareStatement(UPDATE_OPERATION_SQL).use { statement ->
        statement.bindOperationUpdate(operation, update)
        if (statement.executeUpdate() != 1) {
            throw MarketModerationConflict("Market operation journal changed concurrently")
        }
    }
    return checkNotNull(findMarketOperation(operation.operationId))
}

private fun PreparedStatement.bindOperationUpdate(operation: MarketOperationRow, update: OperationUpdate) {
    setString(1, update.state.name)
    setString(2, update.currentChecksum)
    if (update.reviewerId == null) setNull(3, Types.VARCHAR) else setString(3, update.reviewerId.toString())
    setString(4, update.detail)
    setLong(5, update.updatedAt)
    setString(6, operation.operationId.toString())
    setLong(7, operation.revision)
}

internal fun ResultSet.toMarketStallRecord(): MarketStallRecord {
    val ownerType = MarketOwnership.Type.valueOf(getString("owner_type"))
    val ownerId = getString("owner_id").takeIf { ownerType != MarketOwnership.Type.NONE }
    return MarketStallRecord(
        getString("id"),
        getString("world"),
        getString("state"),
        MarketOwnership(ownerType, Optional.ofNullable(ownerId)),
        getLong("moderation_revision"),
        getString("review_due_at") != null,
        Optional.ofNullable(nullableLong("review_due_at")?.let(Instant::ofEpochMilli)),
    )
}

private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
}

private fun ResultSet.nullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private const val INSERT_OPERATION_SQL = """INSERT INTO market_moderation_operations
    (operation_id, target_uuid, case_id, stall_id, state, snapshot_json, snapshot_checksum,
     current_checksum, review_due_at, recovery_until, blacklist_expires_at, reviewer_uuid,
     detail, revision, created_at, updated_at)
    VALUES (?, ?, ?, ?, 'PREPARED', ?, ?, ?, ?, ?, ?, NULL, ?, 1, ?, ?)"""

private const val HOLD_STALL_SQL = """UPDATE stalls SET state = 'MODERATION_HOLD', owner_type = 'NONE', owner_id = '',
    owner_since = NULL, winning_bid = 0, members = '', next_rent_at = NULL,
    moderation_revision = moderation_revision + 1
    WHERE id = ? AND moderation_revision = ?"""

private const val RESTORE_STALL_SQL = """UPDATE stalls SET region_id = ?, world = ?, state = ?, owner_type = ?, owner_id = ?,
    owner_since = ?, winning_bid = ?, rent_mode = ?, rent_pct = ?, rent_flat = ?, members = ?,
    max_members = ?, next_rent_at = ?, kind = ?, extra_entities = ?, extra_total = ?,
    moderation_revision = moderation_revision + 1
    WHERE id = ? AND moderation_revision = ?"""

private const val UPDATE_OPERATION_SQL = """UPDATE market_moderation_operations
    SET state = ?, current_checksum = ?, reviewer_uuid = ?, detail = ?, revision = revision + 1, updated_at = ?
    WHERE operation_id = ? AND revision = ?"""
