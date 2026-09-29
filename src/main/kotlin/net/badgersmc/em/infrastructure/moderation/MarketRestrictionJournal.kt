package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketBlacklistRemoval
import net.enthusia.market.api.moderation.MarketBlacklistRequest
import net.enthusia.market.api.moderation.MarketBlacklistResult
import net.enthusia.market.api.moderation.MarketOperationRequest
import net.enthusia.market.api.moderation.StallBlacklistState
import java.sql.Connection
import java.sql.SQLException
import java.util.Optional
import java.util.UUID
import javax.sql.DataSource

/** Owns player acquisition fences and case-linked Market blacklist policy. */
@Suppress("TooManyFunctions")
internal class MarketRestrictionJournal(
    private val dataSource: DataSource,
    private val clock: java.time.Clock,
) {
    fun getBlacklist(playerId: UUID): Optional<StallBlacklistState> = dataSource.connection.use { connection ->
        Optional.ofNullable(MarketRestrictionBlacklistSql.read(connection, playerId))
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
        val current = MarketRestrictionBlacklistSql.read(connection, removal.targetId())
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
                    MarketRestrictionBlacklistSql.read(connection, removal.targetId()),
                    "Market blacklist changed concurrently",
                )
            }
        }
        val removed = checkNotNull(MarketRestrictionBlacklistSql.read(connection, removal.targetId()))
        result(MarketBlacklistResult.Status.REMOVED, removed, "Market blacklist removed")
    }

    fun reservePlayer(connection: Connection, playerId: UUID, operationId: UUID) {
        val now = clock.millis()
        claimPlayerFence {
            PlayerFenceClaims.claimModeration(connection, playerId, operationId, now)
        }
    }

    fun applyPreparedBlacklist(connection: Connection, request: MarketOperationRequest) {
        val current = MarketRestrictionBlacklistSql.read(connection, request.targetId())
        if (current?.activeAt(clock.instant()) == true) return
        MarketRestrictionBlacklistSql.write(
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
        val current = MarketRestrictionBlacklistSql.read(connection, operation.targetId)
        if (original == null) {
            restoreAbsentBlacklist(connection, operation, current)
            return
        }
        if (current == null) throw MarketModerationConflict("Market blacklist is missing during restoration")
        if (current.matches(original)) return
        requireCurrentBlacklistOperation(current, operation.operationId)
        MarketRestrictionBlacklistSql.writeSnapshot(connection, original, current.revision())
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

    private fun restoreAbsentBlacklist(
        connection: Connection,
        operation: MarketOperationRow,
        current: StallBlacklistState?,
    ) {
        if (current == null) return
        requireCurrentBlacklistOperation(current, operation.operationId)
        connection.prepareStatement(
            """DELETE FROM market_stall_blacklists
               WHERE player_uuid = ? AND operation_id = ? AND revision = ?""",
        ).use { statement ->
            statement.setString(1, operation.targetId.toString())
            statement.setString(2, operation.operationId.toString())
            statement.setLong(3, current.revision())
            if (statement.executeUpdate() != 1) {
                throw MarketModerationConflict("Market blacklist changed concurrently")
            }
        }
    }

    private fun requireCurrentBlacklistOperation(current: StallBlacklistState, operationId: UUID) {
        if (current.operationId() != operationId) {
            throw MarketModerationConflict("A newer market blacklist prevents restoration")
        }
    }

    private fun apply(connection: Connection, request: MarketBlacklistRequest): MarketBlacklistResult {
        claimRestrictionMutation(connection, request.targetId())
        val current = MarketRestrictionBlacklistSql.read(connection, request.targetId())
        replay(current, request)?.let { return it }
        if (current?.activeAt(clock.instant()) == true) {
            throw MarketModerationConflict("Player already has an active market blacklist")
        }
        MarketRestrictionBlacklistSql.write(connection, blacklistWrite(request, current))
        val applied = checkNotNull(MarketRestrictionBlacklistSql.read(connection, request.targetId()))
        return result(MarketBlacklistResult.Status.APPLIED, applied, "Market blacklist applied")
    }

    private fun blacklistWrite(
        request: MarketBlacklistRequest,
        current: StallBlacklistState?,
    ): BlacklistWrite = BlacklistWrite(
        request.operationId(),
        request.targetId(),
        request.caseId(),
        request.expiresAt().orElse(null)?.toEpochMilli(),
        (current?.revision() ?: 0L) + 1L,
        clock.millis(),
    )

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

    private fun hasActiveBlacklist(connection: Connection, playerId: UUID, now: Long): Boolean =
        connection.prepareStatement(
            """SELECT 1 FROM market_stall_blacklists
               WHERE player_uuid = ? AND status = 'ACTIVE'
                 AND (expires_at IS NULL OR expires_at > ?)""",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setLong(2, now)
            statement.executeQuery().use { it.next() }
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
                    nullableLong(result, "acquisition_until"),
                )
            }
        }

    private fun nullableLong(result: java.sql.ResultSet, column: String): Long? {
        val value = result.getLong(column)
        return if (result.wasNull()) null else value
    }

    private fun StallBlacklistState.matches(snapshot: ModeratedBlacklistSnapshot): Boolean =
        playerId().toString() == snapshot.playerId &&
            status().name == snapshot.status &&
            expiresAt().orElse(null)?.toEpochMilli() == snapshot.expiresAt &&
            caseId() == snapshot.caseId &&
            operationId().toString() == snapshot.operationId &&
            revision() == snapshot.revision &&
            updatedAt().toEpochMilli() == snapshot.updatedAt

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

    private fun moderationFence(operationId: UUID): String = "moderation:$operationId"

    private data class PlayerFence(val activeId: String?, val until: Long?) {
        fun activeAt(now: Long): Boolean = activeId != null && (until == null || until > now)
    }
}
