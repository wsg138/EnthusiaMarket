package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.StallBlacklistState
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** JDBC persistence for the restriction journal's blacklist rows. */
internal object MarketRestrictionBlacklistSql {
    fun read(connection: Connection, playerId: UUID): StallBlacklistState? =
        connection.prepareStatement("SELECT * FROM market_stall_blacklists WHERE player_uuid = ?").use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                readRow(result)
            }
        }

    fun write(connection: Connection, write: BlacklistWrite) {
        val existing = read(connection, write.playerId)
        val sql = if (existing == null) INSERT_SQL else UPDATE_SQL
        connection.prepareStatement(sql).use { statement ->
            if (existing == null) bindInsert(statement, write)
            else bindUpdate(statement, write, existing.revision())
            executeWrite(statement)
        }
    }

    fun writeSnapshot(
        connection: Connection,
        snapshot: ModeratedBlacklistSnapshot,
        expectedRevision: Long,
    ) {
        connection.prepareStatement(SNAPSHOT_UPDATE_SQL).use { statement ->
            bindSnapshotUpdate(statement, snapshot, expectedRevision)
            executeWrite(statement)
        }
    }

    private fun readRow(result: ResultSet): StallBlacklistState = StallBlacklistState(
        UUID.fromString(result.getString("player_uuid")),
        StallBlacklistState.Status.valueOf(result.getString("status")),
        Optional.ofNullable(result.nullableLong("expires_at")?.let(Instant::ofEpochMilli)),
        result.getString("case_id"),
        UUID.fromString(result.getString("operation_id")),
        result.getLong("revision"),
        Instant.ofEpochMilli(result.getLong("updated_at")),
    )

    private fun bindInsert(statement: PreparedStatement, write: BlacklistWrite) {
        statement.setString(1, write.playerId.toString())
        statement.setNullableLong(2, write.expiresAt)
        statement.setString(3, write.caseId)
        statement.setString(4, write.operationId.toString())
        statement.setLong(5, write.revision)
        statement.setLong(6, write.updatedAt)
    }

    private fun bindUpdate(statement: PreparedStatement, write: BlacklistWrite, expectedRevision: Long) {
        statement.setNullableLong(1, write.expiresAt)
        statement.setString(2, write.caseId)
        statement.setString(3, write.operationId.toString())
        statement.setLong(4, write.revision)
        statement.setLong(5, write.updatedAt)
        statement.setString(6, write.playerId.toString())
        statement.setLong(7, expectedRevision)
    }

    private fun bindSnapshotUpdate(
        statement: PreparedStatement,
        snapshot: ModeratedBlacklistSnapshot,
        expectedRevision: Long,
    ) {
        statement.setString(1, snapshot.status)
        statement.setNullableLong(2, snapshot.expiresAt)
        statement.setString(3, snapshot.caseId)
        statement.setString(4, snapshot.operationId)
        statement.setLong(5, snapshot.revision)
        statement.setLong(6, snapshot.updatedAt)
        statement.setString(7, snapshot.playerId)
        statement.setLong(8, expectedRevision)
    }

    private fun executeWrite(statement: PreparedStatement) {
        try {
            if (statement.executeUpdate() != 1) {
                throw MarketModerationConflict("Market blacklist changed concurrently")
            }
        } catch (failure: SQLException) {
            throw failure.asBlacklistWriteFailure()
        }
    }

    private fun SQLException.asBlacklistWriteFailure(): Exception =
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

    private const val INSERT_SQL = """INSERT INTO market_stall_blacklists
        (player_uuid, status, expires_at, case_id, operation_id, revision, updated_at)
        VALUES (?, 'ACTIVE', ?, ?, ?, ?, ?)"""

    private const val UPDATE_SQL = """UPDATE market_stall_blacklists
        SET status = 'ACTIVE', expires_at = ?, case_id = ?, operation_id = ?,
            revision = ?, updated_at = ?
        WHERE player_uuid = ? AND revision = ?"""

    private const val SNAPSHOT_UPDATE_SQL = """UPDATE market_stall_blacklists
        SET status = ?, expires_at = ?, case_id = ?, operation_id = ?,
            revision = ?, updated_at = ?
        WHERE player_uuid = ? AND revision = ?"""
}

internal data class BlacklistWrite(
    val operationId: UUID,
    val playerId: UUID,
    val caseId: String,
    val expiresAt: Long?,
    val revision: Long,
    val updatedAt: Long,
)
