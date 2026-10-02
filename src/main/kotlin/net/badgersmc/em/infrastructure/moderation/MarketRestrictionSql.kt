package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketBlacklistRemoval
import net.enthusia.market.api.moderation.StallBlacklistState
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.util.Optional
import java.util.UUID

internal fun Connection.readMarketBlacklist(playerId: UUID): StallBlacklistState? =
    prepareStatement("SELECT * FROM market_stall_blacklists WHERE player_uuid = ?").use { statement ->
        statement.setString(1, playerId.toString())
        statement.executeQuery().use { result ->
            if (!result.next()) return null
            result.toBlacklistState()
        }
    }

private fun ResultSet.toBlacklistState(): StallBlacklistState = StallBlacklistState(
    UUID.fromString(getString("player_uuid")),
    StallBlacklistState.Status.valueOf(getString("status")),
    Optional.ofNullable(nullableLong("expires_at")?.let(Instant::ofEpochMilli)),
    getString("case_id"),
    UUID.fromString(getString("operation_id")),
    getLong("revision"),
    Instant.ofEpochMilli(getLong("updated_at")),
)

internal fun Connection.hasActiveMarketBlacklist(playerId: UUID, now: Long): Boolean =
    prepareStatement(
        """SELECT 1 FROM market_stall_blacklists
           WHERE player_uuid = ? AND status = 'ACTIVE'
             AND (expires_at IS NULL OR expires_at > ?)""",
    ).use { statement ->
        statement.setString(1, playerId.toString())
        statement.setLong(2, now)
        statement.executeQuery().use(ResultSet::next)
    }

internal fun Connection.hasActiveMarketPlayerFence(playerId: UUID, now: Long): Boolean =
    prepareStatement("SELECT active_acquisition_id, acquisition_until FROM market_player_fences WHERE player_uuid = ?")
        .use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result ->
                if (!result.next()) return false
                val activeId = result.getString("active_acquisition_id")
                val until = result.nullableLong("acquisition_until")
                activeId != null && (until == null || until > now)
            }
        }

internal fun Connection.writeMarketBlacklist(write: BlacklistWrite) {
    val existing = readMarketBlacklist(write.playerId)
    val sql = if (existing == null) INSERT_BLACKLIST_SQL else UPDATE_BLACKLIST_SQL
    prepareStatement(sql).use { statement ->
        if (existing == null) statement.bindBlacklistInsert(write)
        else statement.bindBlacklistUpdate(write, existing.revision())
        statement.executeBlacklistWrite()
    }
}

private fun PreparedStatement.bindBlacklistInsert(write: BlacklistWrite) {
    setString(1, write.playerId.toString())
    setNullableLong(2, write.expiresAt)
    setString(3, write.caseId)
    setString(4, write.operationId.toString())
    setLong(5, write.revision)
    setLong(6, write.updatedAt)
}

private fun PreparedStatement.bindBlacklistUpdate(write: BlacklistWrite, expectedRevision: Long) {
    setNullableLong(1, write.expiresAt)
    setString(2, write.caseId)
    setString(3, write.operationId.toString())
    setLong(4, write.revision)
    setLong(5, write.updatedAt)
    setString(6, write.playerId.toString())
    setLong(7, expectedRevision)
}

internal fun Connection.writeMarketBlacklistSnapshot(
    snapshot: ModeratedBlacklistSnapshot,
    expectedRevision: Long,
) {
    prepareStatement(RESTORE_BLACKLIST_SQL).use { statement ->
        statement.setString(1, snapshot.status)
        statement.setNullableLong(2, snapshot.expiresAt)
        statement.setString(3, snapshot.caseId)
        statement.setString(4, snapshot.operationId)
        statement.setLong(5, snapshot.revision)
        statement.setLong(6, snapshot.updatedAt)
        statement.setString(7, snapshot.playerId)
        statement.setLong(8, expectedRevision)
        statement.executeBlacklistWrite()
    }
}

internal fun Connection.markMarketBlacklistRemoved(removal: MarketBlacklistRemoval, updatedAt: Long): Boolean {
    prepareStatement(REMOVE_BLACKLIST_SQL).use { statement ->
        statement.setString(1, removal.operationId().toString())
        statement.setLong(2, updatedAt)
        statement.setString(3, removal.targetId().toString())
        statement.setLong(4, removal.expectedRevision())
        return statement.executeUpdate() == 1
    }
}

internal fun Connection.deleteRestoredBlacklist(
    operation: MarketOperationRow,
    current: StallBlacklistState,
): Boolean = prepareStatement(
    "DELETE FROM market_stall_blacklists WHERE player_uuid = ? AND operation_id = ? AND revision = ?",
).use { statement ->
    statement.setString(1, operation.targetId.toString())
    statement.setString(2, operation.operationId.toString())
    statement.setLong(3, current.revision())
    statement.executeUpdate() == 1
}

internal fun Connection.releaseMarketPlayerReservation(operation: MarketOperationRow, updatedAt: Long): Boolean =
    prepareStatement(RELEASE_PLAYER_FENCE_SQL).use { statement ->
        statement.setLong(1, updatedAt)
        statement.setString(2, operation.targetId.toString())
        statement.setString(3, "moderation:${operation.operationId}")
        statement.executeUpdate() == 1
    }

private fun PreparedStatement.executeBlacklistWrite() {
    try {
        requireSingleBlacklistWrite(executeUpdate())
    } catch (failure: SQLException) {
        throw failure.toBlacklistWriteFailure()
    }
}

private fun requireSingleBlacklistWrite(updated: Int) {
    if (updated != 1) {
        throw MarketModerationConflict("Market blacklist changed concurrently")
    }
}

private fun SQLException.toBlacklistWriteFailure(): Throwable =
    if (isDuplicateKeyViolation() || isTransactionContention()) {
        MarketModerationConflict("Market blacklist changed concurrently")
    } else {
        this
    }

private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
}

private fun ResultSet.nullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private const val INSERT_BLACKLIST_SQL = """INSERT INTO market_stall_blacklists
    (player_uuid, status, expires_at, case_id, operation_id, revision, updated_at)
    VALUES (?, 'ACTIVE', ?, ?, ?, ?, ?)"""

private const val UPDATE_BLACKLIST_SQL = """UPDATE market_stall_blacklists
    SET status = 'ACTIVE', expires_at = ?, case_id = ?, operation_id = ?, revision = ?, updated_at = ?
    WHERE player_uuid = ? AND revision = ?"""

private const val RESTORE_BLACKLIST_SQL = """UPDATE market_stall_blacklists
    SET status = ?, expires_at = ?, case_id = ?, operation_id = ?, revision = ?, updated_at = ?
    WHERE player_uuid = ? AND revision = ?"""

private const val REMOVE_BLACKLIST_SQL = """UPDATE market_stall_blacklists
    SET status = 'REMOVED', expires_at = NULL, operation_id = ?, revision = revision + 1, updated_at = ?
    WHERE player_uuid = ? AND revision = ?"""

private const val RELEASE_PLAYER_FENCE_SQL = """UPDATE market_player_fences
    SET active_acquisition_id = NULL, acquisition_until = NULL, revision = revision + 1, updated_at = ?
    WHERE player_uuid = ? AND active_acquisition_id = ?"""
