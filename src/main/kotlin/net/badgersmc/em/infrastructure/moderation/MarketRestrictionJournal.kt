package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketBlacklistRemoval
import net.enthusia.market.api.moderation.MarketBlacklistRequest
import net.enthusia.market.api.moderation.MarketBlacklistResult
import net.enthusia.market.api.moderation.MarketOperationRequest
import net.enthusia.market.api.moderation.StallBlacklistState
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import javax.sql.DataSource

/** Owns player acquisition fences and case-linked Market blacklist rows. */
@Suppress("TooManyFunctions")
internal class MarketRestrictionJournal(
    private val dataSource: DataSource,
    private val clock: Clock,
) {
    fun getBlacklist(playerId: UUID): Optional<StallBlacklistState> = dataSource.connection.use { connection ->
        Optional.ofNullable(readBlacklist(connection, playerId))
    }

    fun canAcquire(playerId: UUID): Boolean = dataSource.connection.use { connection ->
        val now = clock.millis()
        !hasActiveBlacklist(connection, playerId, now) && !hasActivePlayerFence(connection, playerId, now)
    }

    fun apply(request: MarketBlacklistRequest): MarketBlacklistResult = blacklistTransaction {
        apply(it, request)
    }

    fun remove(removal: MarketBlacklistRemoval): MarketBlacklistResult = blacklistTransaction { connection ->
        claimRestrictionMutation(connection, removal.targetId())
        val current = readBlacklist(connection, removal.targetId())
            ?: return@blacklistTransaction result(
                MarketBlacklistResult.Status.REJECTED,
                null,
                "Player does not have a market blacklist record",
            )
        if (current.operationId() == removal.operationId() &&
            current.status() == StallBlacklistState.Status.REMOVED
        ) {
            return@blacklistTransaction result(
                MarketBlacklistResult.Status.REPLAYED,
                current,
                "Market blacklist was already removed",
            )
        }
        if (current.caseId() != removal.caseId() || current.revision() != removal.expectedRevision()) {
            return@blacklistTransaction result(
                MarketBlacklistResult.Status.CONFLICT,
                current,
                "Market blacklist case or revision changed",
            )
        }
        connection.prepareStatement(
            """UPDATE market_stall_blacklists
               SET status = 'REMOVED', expires_at = NULL, operation_id = ?,
                   revision = revision + 1, updated_at = ?
               WHERE player_uuid = ? AND revision = ?""",
        ).use { statement ->
            statement.setString(1, removal.operationId().toString())
            statement.setLong(2, clock.millis())
            statement.setString(3, removal.targetId().toString())
            statement.setLong(4, removal.expectedRevision())
            if (statement.executeUpdate() != 1) {
                return@blacklistTransaction result(
                    MarketBlacklistResult.Status.CONFLICT,
                    readBlacklist(connection, removal.targetId()),
                    "Market blacklist changed concurrently",
                )
            }
        }
        val removed = checkNotNull(readBlacklist(connection, removal.targetId()))
        result(MarketBlacklistResult.Status.REMOVED, removed, "Market blacklist removed")
    }

    fun reservePlayer(connection: Connection, playerId: UUID, operationId: UUID) {
        val now = clock.millis()
        claimPlayerFence {
            PlayerFenceClaims.claimModeration(connection, playerId, operationId, now)
        }
    }

    fun applyPreparedBlacklist(connection: Connection, request: MarketOperationRequest) {
        val current = readBlacklist(connection, request.targetId())
        if (current?.activeAt(clock.instant()) == true) return
        writeBlacklist(
            connection,
            BlacklistWrite(
                request.operationId(),
                request.targetId(),
                request.caseId(),
                request.blacklistExpiresAt().orElse(null)?.toEpochMilli(),
                (current?.revision() ?: 0L) + 1L,
                clock.millis(),
            ),
        )
    }

    fun restoreBlacklist(
        connection: Connection,
        operation: MarketOperationRow,
        original: ModeratedBlacklistSnapshot?,
    ) {
        val current = readBlacklist(connection, operation.targetId)
        if (original == null) {
            if (current == null) return
            if (current.operationId() != operation.operationId) {
                throw MarketModerationConflict("A newer market blacklist prevents restoration")
            }
            connection.prepareStatement("DELETE FROM market_stall_blacklists WHERE player_uuid = ?").use { statement ->
                statement.setString(1, operation.targetId.toString())
                statement.executeUpdate()
            }
            return
        }
        writeBlacklistSnapshot(connection, original)
    }

    fun releasePlayerReservation(connection: Connection, operation: MarketOperationRow) {
        connection.prepareStatement(
            """UPDATE market_player_fences
               SET active_acquisition_id = NULL, acquisition_until = NULL,
                   revision = revision + 1, updated_at = ?
               WHERE player_uuid = ? AND active_acquisition_id = ?""",
        ).use { statement ->
            statement.setLong(1, clock.millis())
            statement.setString(2, operation.targetId.toString())
            statement.setString(3, moderationFence(operation.operationId))
            if (statement.executeUpdate() != 1) {
                throw MarketModerationConflict("Market player reservation is missing")
            }
        }
    }

    private fun apply(connection: Connection, request: MarketBlacklistRequest): MarketBlacklistResult {
        claimRestrictionMutation(connection, request.targetId())
        val current = readBlacklist(connection, request.targetId())
        replay(current, request)?.let { return it }
        if (current?.activeAt(clock.instant()) == true) {
            throw MarketModerationConflict("Player already has an active market blacklist")
        }
        writeBlacklist(
            connection,
            BlacklistWrite(
                request.operationId(),
                request.targetId(),
                request.caseId(),
                request.expiresAt().orElse(null)?.toEpochMilli(),
                (current?.revision() ?: 0L) + 1L,
                clock.millis(),
            ),
        )
        val applied = checkNotNull(readBlacklist(connection, request.targetId()))
        return result(MarketBlacklistResult.Status.APPLIED, applied, "Market blacklist applied")
    }

    private fun replay(
        current: StallBlacklistState?,
        request: MarketBlacklistRequest,
    ): MarketBlacklistResult? {
        if (current?.operationId() != request.operationId()) return null
        if (current.caseId() != request.caseId() || current.expiresAt() != request.expiresAt()) {
            throw MarketModerationConflict("Operation id belongs to a different blacklist request")
        }
        return result(MarketBlacklistResult.Status.REPLAYED, current, "Market blacklist already applied")
    }

    private fun claimRestrictionMutation(connection: Connection, playerId: UUID) {
        val now = clock.millis()
        claimPlayerFence {
            PlayerFenceClaims.claimRestrictionMutation(connection, playerId, now)
        }
    }

    private inline fun claimPlayerFence(claim: () -> Boolean) {
        val claimed = try {
            claim()
        } catch (failure: SQLException) {
            rethrowFenceFailure(failure)
        }
        if (!claimed) {
            throw MarketModerationConflict("Player has an acquisition or moderation operation in progress")
        }
    }

    private fun rethrowFenceFailure(failure: SQLException): Nothing {
        if (failure.isTransactionContention()) {
            throw MarketModerationConflict("Player fence changed concurrently")
        }
        throw failure
    }

    private fun readBlacklist(connection: Connection, playerId: UUID): StallBlacklistState? =
        connection.prepareStatement("SELECT * FROM market_stall_blacklists WHERE player_uuid = ?").use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                StallBlacklistState(
                    UUID.fromString(result.getString("player_uuid")),
                    StallBlacklistState.Status.valueOf(result.getString("status")),
                    Optional.ofNullable(result.nullableLong("expires_at")?.let(Instant::ofEpochMilli)),
                    result.getString("case_id"),
                    UUID.fromString(result.getString("operation_id")),
                    result.getLong("revision"),
                    Instant.ofEpochMilli(result.getLong("updated_at")),
                )
            }
        }

    private fun hasActiveBlacklist(connection: Connection, playerId: UUID, now: Long): Boolean =
        connection.prepareStatement(
            """SELECT 1 FROM market_stall_blacklists
               WHERE player_uuid = ? AND status = 'ACTIVE'
                 AND (expires_at IS NULL OR expires_at > ?)""",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setLong(2, now)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun hasActivePlayerFence(connection: Connection, playerId: UUID, now: Long): Boolean =
        readPlayerFence(connection, playerId)?.activeAt(now) == true

    private fun readPlayerFence(connection: Connection, playerId: UUID): PlayerFence? =
        connection.prepareStatement("SELECT * FROM market_player_fences WHERE player_uuid = ?").use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                PlayerFence(
                    result.getString("active_acquisition_id"),
                    result.nullableLong("acquisition_until"),
                )
            }
        }

    private fun writeBlacklist(
        connection: Connection,
        write: BlacklistWrite,
    ) {
        val existing = readBlacklist(connection, write.playerId)
        val sql = if (existing == null) {
            """INSERT INTO market_stall_blacklists
               (player_uuid, status, expires_at, case_id, operation_id, revision, updated_at)
               VALUES (?, 'ACTIVE', ?, ?, ?, ?, ?)"""
        } else {
            """UPDATE market_stall_blacklists
               SET status = 'ACTIVE', expires_at = ?, case_id = ?, operation_id = ?,
                   revision = ?, updated_at = ?
               WHERE player_uuid = ? AND revision = ?"""
        }
        connection.prepareStatement(sql).use { statement ->
            if (existing == null) {
                bindBlacklistInsert(statement, write)
            } else {
                bindBlacklistUpdate(statement, write, existing.revision())
            }
            executeBlacklistWrite(statement)
        }
    }

    private fun bindBlacklistInsert(
        statement: java.sql.PreparedStatement,
        write: BlacklistWrite,
    ) {
        statement.setString(1, write.playerId.toString())
        statement.setNullableLong(2, write.expiresAt)
        statement.setString(3, write.caseId)
        statement.setString(4, write.operationId.toString())
        statement.setLong(5, write.revision)
        statement.setLong(6, write.updatedAt)
    }

    private fun bindBlacklistUpdate(
        statement: java.sql.PreparedStatement,
        write: BlacklistWrite,
        expectedRevision: Long,
    ) {
        statement.setNullableLong(1, write.expiresAt)
        statement.setString(2, write.caseId)
        statement.setString(3, write.operationId.toString())
        statement.setLong(4, write.revision)
        statement.setLong(5, write.updatedAt)
        statement.setString(6, write.playerId.toString())
        statement.setLong(7, expectedRevision)
    }

    private fun executeBlacklistWrite(statement: java.sql.PreparedStatement) {
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

    private fun writeBlacklistSnapshot(connection: Connection, snapshot: ModeratedBlacklistSnapshot) {
        val existing = readBlacklist(connection, UUID.fromString(snapshot.playerId))
        val sql = if (existing == null) {
            """INSERT INTO market_stall_blacklists
               (player_uuid, status, expires_at, case_id, operation_id, revision, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)"""
        } else {
            """UPDATE market_stall_blacklists
               SET status = ?, expires_at = ?, case_id = ?, operation_id = ?,
                   revision = ?, updated_at = ? WHERE player_uuid = ?"""
        }
        connection.prepareStatement(sql).use { statement ->
            if (existing == null) bindSnapshotInsert(statement, snapshot)
            else bindSnapshotUpdate(statement, snapshot)
            statement.executeUpdate()
        }
    }

    private fun bindSnapshotInsert(statement: java.sql.PreparedStatement, snapshot: ModeratedBlacklistSnapshot) {
        statement.setString(1, snapshot.playerId)
        statement.setString(2, snapshot.status)
        statement.setNullableLong(3, snapshot.expiresAt)
        statement.setString(4, snapshot.caseId)
        statement.setString(5, snapshot.operationId)
        statement.setLong(6, snapshot.revision)
        statement.setLong(7, snapshot.updatedAt)
    }

    private fun bindSnapshotUpdate(statement: java.sql.PreparedStatement, snapshot: ModeratedBlacklistSnapshot) {
        statement.setString(1, snapshot.status)
        statement.setNullableLong(2, snapshot.expiresAt)
        statement.setString(3, snapshot.caseId)
        statement.setString(4, snapshot.operationId)
        statement.setLong(5, snapshot.revision)
        statement.setLong(6, snapshot.updatedAt)
        statement.setString(7, snapshot.playerId)
    }

    private fun result(
        status: MarketBlacklistResult.Status,
        blacklist: StallBlacklistState?,
        detail: String,
    ): MarketBlacklistResult = MarketBlacklistResult(status, Optional.ofNullable(blacklist), detail)

    private inline fun blacklistTransaction(
        block: (Connection) -> MarketBlacklistResult,
    ): MarketBlacklistResult = try {
        dataSource.inTransaction(block)
    } catch (conflict: MarketModerationConflict) {
        result(MarketBlacklistResult.Status.CONFLICT, null, conflict.message ?: "Blacklist conflict")
    } catch (failure: SQLException) {
        if (!failure.isTransactionContention()) throw failure
        result(MarketBlacklistResult.Status.CONFLICT, null, "Market blacklist changed concurrently")
    }

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
    }

    private fun ResultSet.nullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private fun moderationFence(operationId: UUID): String = "moderation:$operationId"

    private data class PlayerFence(val activeId: String?, val until: Long?) {
        fun activeAt(now: Long): Boolean = activeId != null && (until == null || until > now)
    }

    private data class BlacklistWrite(
        val operationId: UUID,
        val playerId: UUID,
        val caseId: String,
        val expiresAt: Long?,
        val revision: Long,
        val updatedAt: Long,
    )
}
