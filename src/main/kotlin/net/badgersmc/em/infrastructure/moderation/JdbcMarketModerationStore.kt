package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketBlacklistRemoval
import net.enthusia.market.api.moderation.MarketBlacklistRequest
import net.enthusia.market.api.moderation.MarketBlacklistResult
import net.enthusia.market.api.moderation.MarketConfiscationApproval
import net.enthusia.market.api.moderation.MarketOperationRecord
import net.enthusia.market.api.moderation.MarketOperationRequest
import net.enthusia.market.api.moderation.MarketOperationResult
import net.enthusia.market.api.moderation.MarketOwnership
import net.enthusia.market.api.moderation.MarketRestoreRequest
import net.enthusia.market.api.moderation.MarketStallRecord
import net.enthusia.market.api.moderation.StallBlacklistState
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import javax.sql.DataSource

/** Durable implementation of the Market side of the Staff moderation contract. */
@Suppress("TooManyFunctions")
internal class JdbcMarketModerationStore(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    private val snapshotCodec: MarketSnapshotCodec = MarketSnapshotCodec(),
) {
    private val restrictions = MarketRestrictionJournal(dataSource, clock)

    fun findStalls(playerId: UUID): List<MarketStallRecord> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT s.*, o.review_due_at
               FROM stalls s
               LEFT JOIN market_moderation_locks l ON l.stall_id = s.id
               LEFT JOIN market_moderation_operations o ON o.operation_id = l.operation_id
               WHERE s.owner_type = 'SOLO' AND s.owner_id = ?
               ORDER BY s.id
               LIMIT ?""",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setInt(2, MAXIMUM_STALLS_PER_PLAYER + 1)
            statement.executeQuery().use { result ->
                val stalls = buildList {
                    while (result.next()) add(MarketModerationStoreSql.stallRecord(result))
                }
                if (stalls.size > MAXIMUM_STALLS_PER_PLAYER) {
                    throw MarketModerationRejected(
                        "Player owns more than $MAXIMUM_STALLS_PER_PLAYER market stalls",
                    )
                }
                stalls
            }
        }
    }

    fun getBlacklist(playerId: UUID): Optional<StallBlacklistState> = restrictions.getBlacklist(playerId)

    fun canAcquire(playerId: UUID): Boolean = restrictions.canAcquire(playerId)

    fun findOperation(operationId: UUID): Optional<MarketOperationRecord> = dataSource.connection.use { connection ->
        Optional.ofNullable(connection.findMarketOperation(operationId)?.toRecord())
    }

    fun regionAccess(operationId: UUID): MarketRegionAccessSnapshot? = dataSource.connection.use { connection ->
        val operation = connection.findMarketOperation(operationId) ?: return@use null
        val stall = snapshotCodec.decodeVerified(operation.snapshotJson, operation.snapshotChecksum)?.stall
            ?: throw MarketModerationConflict("Stored market snapshot failed its integrity check")
        if (stall.id != operation.stallId) {
            throw MarketModerationConflict("Stored market snapshot belongs to a different stall")
        }
        MarketRegionAccessSnapshot(
            stall.world,
            stall.regionId,
            MarketOwnership.Type.valueOf(stall.ownerType),
            stall.ownerId,
            stall.members.map(UUID::fromString).toSet(),
        )
    }

    fun prepare(request: MarketOperationRequest): MarketOperationResult =
        moderatedTransaction { connection -> prepare(connection, request) }

    fun confiscate(approval: MarketConfiscationApproval): MarketOperationResult =
        moderatedTransaction { connection -> confiscate(connection, approval) }

    private fun confiscate(
        connection: Connection,
        approval: MarketConfiscationApproval,
    ): MarketOperationResult {
        val operation = connection.findMarketOperation(approval.operationId())
            ?: return operationResult(
                MarketOperationResult.Status.REJECTED,
                null,
                "Market operation does not exist",
            )
        if (operation.state == MarketOperationRecord.State.MODERATION_HOLD) {
            return operationResult(
                MarketOperationResult.Status.REPLAYED,
                operation,
                "Market confiscation was already reviewed",
            )
        }
        if (operation.state != MarketOperationRecord.State.PREPARED) {
            return conflict(operation, "Only a prepared market operation can be confiscated")
        }
        if (operation.snapshotChecksum != approval.expectedSnapshotChecksum()) {
            return conflict(operation, "Prepared snapshot checksum does not match")
        }
        if (verifiedOriginal(operation) == null) {
            return quarantine(connection, operation, "Stored market snapshot failed its integrity check")
        }
        val current = snapshotCodec.capture(connection, operation.stallId, operation.targetId)
        if (current.checksum != operation.currentChecksum) {
            return quarantine(connection, operation, "Prepared market state changed before review")
        }
        holdStall(connection, operation, current.stallRevision)
        val held = snapshotCodec.capture(connection, operation.stallId, operation.targetId)
        val updated = MarketModerationStoreSql.updateOperation(
            connection,
            operation,
            OperationUpdate(
                MarketOperationRecord.State.MODERATION_HOLD,
                held.checksum,
                approval.reviewerId(),
                "Ownership placed in a reviewed moderation hold",
                approval.reviewedAt().toEpochMilli(),
            ),
        )
        return operationResult(MarketOperationResult.Status.HELD, updated, updated.detail)
    }

    fun restore(request: MarketRestoreRequest): MarketOperationResult = moderatedTransaction { connection ->
        val operation = connection.findMarketOperation(request.operationId())
            ?: return@moderatedTransaction operationResult(
                MarketOperationResult.Status.REJECTED,
                null,
                "Market operation does not exist",
            )
        if (operation.state == MarketOperationRecord.State.RESTORED) {
            return@moderatedTransaction operationResult(
                MarketOperationResult.Status.REPLAYED,
                operation,
                "Market ownership was already restored",
            )
        }
        if (operation.state != MarketOperationRecord.State.MODERATION_HOLD) {
            return@moderatedTransaction conflict(operation, "Only a reviewed moderation hold can be restored")
        }
        if (operation.currentChecksum != request.expectedCurrentChecksum()) {
            return@moderatedTransaction conflict(operation, "Held market checksum does not match")
        }
        val original = verifiedOriginal(operation)
            ?: return@moderatedTransaction quarantine(
                connection,
                operation,
                "Stored market snapshot failed its integrity check",
            )
        val current = snapshotCodec.capture(connection, operation.stallId, operation.targetId)
        if (current.checksum != operation.currentChecksum) {
            return@moderatedTransaction quarantine(connection, operation, "Held market state changed before restoration")
        }
        restoreOriginal(connection, operation, original, current.stallRevision)
        releaseReservations(connection, operation)
        val updated = MarketModerationStoreSql.updateOperation(
            connection,
            operation,
            OperationUpdate(
                MarketOperationRecord.State.RESTORED,
                operation.snapshotChecksum,
                request.reviewerId(),
                "Original market ownership and shop state restored",
                clock.millis(),
            ),
        )
        operationResult(MarketOperationResult.Status.RESTORED, updated, updated.detail)
    }

    fun release(operationId: UUID, expectedSnapshotChecksum: String): MarketOperationResult =
        moderatedTransaction { connection ->
            val operation = connection.findMarketOperation(operationId)
                ?: return@moderatedTransaction operationResult(
                    MarketOperationResult.Status.REJECTED,
                    null,
                    "Market operation does not exist",
                )
            if (operation.state == MarketOperationRecord.State.RELEASED) {
                return@moderatedTransaction operationResult(
                    MarketOperationResult.Status.REPLAYED,
                    operation,
                    "Prepared market operation was already released",
                )
            }
            if (operation.state != MarketOperationRecord.State.PREPARED) {
                return@moderatedTransaction conflict(operation, "Only a prepared market operation can be released")
            }
            if (operation.snapshotChecksum != expectedSnapshotChecksum.lowercase()) {
                return@moderatedTransaction conflict(operation, "Prepared snapshot checksum does not match")
            }
            val original = verifiedOriginal(operation)
                ?: return@moderatedTransaction quarantine(
                    connection,
                    operation,
                    "Stored market snapshot failed its integrity check",
                )
            val current = snapshotCodec.capture(connection, operation.stallId, operation.targetId)
            if (current.checksum != operation.currentChecksum) {
                return@moderatedTransaction quarantine(
                    connection,
                    operation,
                    "Prepared market state changed before release",
                )
            }
            restoreOriginal(connection, operation, original, current.stallRevision)
            releaseReservations(connection, operation)
            val updated = MarketModerationStoreSql.updateOperation(
                connection,
                operation,
                OperationUpdate(
                    MarketOperationRecord.State.RELEASED,
                    operation.snapshotChecksum,
                    null,
                    "Prepared market operation released without ownership removal",
                    clock.millis(),
                ),
            )
            operationResult(MarketOperationResult.Status.RELEASED, updated, updated.detail)
        }

    fun applyBlacklist(request: MarketBlacklistRequest): MarketBlacklistResult = restrictions.apply(request)

    fun removeBlacklist(removal: MarketBlacklistRemoval): MarketBlacklistResult = restrictions.remove(removal)

    private fun prepare(connection: Connection, request: MarketOperationRequest): MarketOperationResult {
        lockSnapshotRows(connection, request.stallId())
        connection.findMarketOperation(request.operationId())?.let { existing ->
            if (!existing.matches(request)) {
                throw MarketModerationConflict("Operation id belongs to a different market request")
            }
            return operationResult(MarketOperationResult.Status.REPLAYED, existing, "Market operation already exists")
        }
        reserveStall(connection, request.stallId(), request.operationId())
        val original = snapshotCodec.capture(connection, request.stallId(), request.targetId())
        requireTargetOwnership(original, request.targetId())
        restrictions.reservePlayer(connection, request.targetId(), request.operationId())
        advanceStallRevision(connection, request.stallId(), original.stallRevision)
        freezeShops(connection, request.stallId())
        restrictions.applyPreparedBlacklist(connection, request)
        val prepared = snapshotCodec.capture(connection, request.stallId(), request.targetId())
        val now = clock.millis()
        insertOperation(connection, request, original, prepared.checksum, now)
        val operation = checkNotNull(connection.findMarketOperation(request.operationId()))
        return operationResult(MarketOperationResult.Status.PREPARED, operation, operation.detail)
    }

    private fun lockSnapshotRows(connection: Connection, stallId: String) {
        connection.prepareStatement(
            "UPDATE stalls SET moderation_revision = moderation_revision WHERE id = ?",
        ).use { statement ->
            statement.setString(1, stallId)
            statement.executeUpdate()
        }
        connection.prepareStatement("SELECT 1 FROM stalls WHERE id = ?").use { statement ->
            statement.setString(1, stallId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw MarketModerationRejected("Market stall '$stallId' does not exist")
            }
        }
        connection.prepareStatement(
            "UPDATE shop_items SET stock_count = stock_count WHERE stall_id = ?",
        ).use { statement ->
            statement.setString(1, stallId)
            statement.executeUpdate()
        }
    }

    private fun requireTargetOwnership(snapshot: CapturedMarketSnapshot, targetId: UUID) {
        val stall = snapshot.snapshot.stall
        if (stall.ownerType != MarketOwnership.Type.SOLO.name || stall.ownerId != targetId.toString()) {
            throw MarketModerationRejected("Target does not own the requested market stall")
        }
        if (stall.state == "MODERATION_HOLD") {
            throw MarketModerationConflict("Market stall is already in a moderation hold")
        }
    }

    private fun reserveStall(connection: Connection, stallId: String, operationId: UUID) {
        try {
            connection.prepareStatement(
                "INSERT INTO market_moderation_locks(stall_id, operation_id, acquired_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, stallId)
                statement.setString(2, operationId.toString())
                statement.setLong(3, clock.millis())
                statement.executeUpdate()
            }
        } catch (failure: SQLException) {
            if (failure.isDuplicateKeyViolation() || failure.isTransactionContention()) {
                throw MarketModerationConflict("Market stall is reserved or changed by another operation")
            }
            throw failure
        }
    }

    private fun advanceStallRevision(connection: Connection, stallId: String, expectedRevision: Long) {
        connection.prepareStatement(
            "UPDATE stalls SET moderation_revision = moderation_revision + 1 WHERE id = ? AND moderation_revision = ?",
        ).use { statement ->
            statement.setString(1, stallId)
            statement.setLong(2, expectedRevision)
            if (statement.executeUpdate() != 1) {
                throw MarketModerationConflict("Market stall changed while it was being reserved")
            }
        }
    }

    private fun freezeShops(connection: Connection, stallId: String) {
        connection.prepareStatement("UPDATE shop_items SET frozen = 1 WHERE stall_id = ?").use { statement ->
            statement.setString(1, stallId)
            statement.executeUpdate()
        }
    }

    private fun insertOperation(
        connection: Connection,
        request: MarketOperationRequest,
        original: CapturedMarketSnapshot,
        currentChecksum: String,
        now: Long,
    ) {
        try {
            connection.prepareStatement(
                """INSERT INTO market_moderation_operations
                   (operation_id, target_uuid, case_id, stall_id, state,
                    snapshot_json, snapshot_checksum, current_checksum,
                    review_due_at, recovery_until, blacklist_expires_at, reviewer_uuid, detail,
                    revision, created_at, updated_at)
                   VALUES (?, ?, ?, ?, 'PREPARED', ?, ?, ?, ?, ?, ?, NULL, ?, 1, ?, ?)""",
            ).use { statement ->
                statement.setString(1, request.operationId().toString())
                statement.setString(2, request.targetId().toString())
                statement.setString(3, request.caseId())
                statement.setString(4, request.stallId())
                statement.setString(5, original.json)
                statement.setString(6, original.checksum)
                statement.setString(7, currentChecksum)
                statement.setLong(8, request.reviewDueAt().toEpochMilli())
                statement.setLong(9, request.recoveryUntil().toEpochMilli())
                statement.setNullableLong(10, request.blacklistExpiresAt().map(Instant::toEpochMilli).orElse(null))
                statement.setString(11, "Market stall reserved pending explicit Staff review")
                statement.setLong(12, now)
                statement.setLong(13, now)
                statement.executeUpdate()
            }
        } catch (failure: SQLException) {
            if (failure.isDuplicateKeyViolation()) {
                throw MarketModerationConflict("Case already has a moderation operation for this stall")
            }
            throw failure
        }
    }

    private fun holdStall(connection: Connection, operation: MarketOperationRow, expectedRevision: Long) {
        connection.prepareStatement(
            """UPDATE stalls SET state = 'MODERATION_HOLD', owner_type = 'NONE', owner_id = '',
               owner_since = NULL, winning_bid = 0, members = '', next_rent_at = NULL,
               moderation_revision = moderation_revision + 1
               WHERE id = ? AND moderation_revision = ?""",
        ).use { statement ->
            statement.setString(1, operation.stallId)
            statement.setLong(2, expectedRevision)
            if (statement.executeUpdate() != 1) {
                throw MarketModerationConflict("Market stall changed during reviewed confiscation")
            }
        }
    }

    private fun verifiedOriginal(operation: MarketOperationRow): MarketSnapshot? =
        snapshotCodec.decodeVerified(operation.snapshotJson, operation.snapshotChecksum)

    private fun restoreOriginal(
        connection: Connection,
        operation: MarketOperationRow,
        original: MarketSnapshot,
        expectedRevision: Long,
    ) {
        if (original.stall.id != operation.stallId) {
            throw MarketModerationConflict("Stored market snapshot belongs to a different stall")
        }
        MarketModerationStoreSql.restoreStall(connection, original.stall, expectedRevision)
        MarketModerationStoreSql.restoreShopFlags(connection, original.shops)
        restrictions.restoreBlacklist(connection, operation, original.blacklist)
    }

    private fun releaseReservations(connection: Connection, operation: MarketOperationRow) {
        connection.prepareStatement(
            "DELETE FROM market_moderation_locks WHERE stall_id = ? AND operation_id = ?",
        ).use { statement ->
            statement.setString(1, operation.stallId)
            statement.setString(2, operation.operationId.toString())
            if (statement.executeUpdate() != 1) {
                throw MarketModerationConflict("Market moderation reservation is missing")
            }
        }
        restrictions.releasePlayerReservation(connection, operation)
    }

    private fun quarantine(
        connection: Connection,
        operation: MarketOperationRow,
        detail: String,
    ): MarketOperationResult {
        val updated = MarketModerationStoreSql.updateOperation(
            connection,
            operation,
            OperationUpdate(
                MarketOperationRecord.State.QUARANTINED,
                operation.currentChecksum ?: operation.snapshotChecksum,
                operation.reviewerId,
                detail,
                clock.millis(),
            ),
        )
        return operationResult(MarketOperationResult.Status.QUARANTINED, updated, detail)
    }

    private fun conflict(operation: MarketOperationRow, detail: String): MarketOperationResult =
        operationResult(MarketOperationResult.Status.CONFLICT, operation, detail)

    private fun operationResult(
        status: MarketOperationResult.Status,
        operation: MarketOperationRow?,
        detail: String,
    ): MarketOperationResult = MarketOperationResult(status, Optional.ofNullable(operation?.toRecord()), detail)

    private inline fun moderatedTransaction(
        block: (Connection) -> MarketOperationResult,
    ): MarketOperationResult = try {
        dataSource.inTransaction(block)
    } catch (conflict: MarketModerationConflict) {
        operationResult(MarketOperationResult.Status.CONFLICT, null, conflict.message ?: "Market operation conflict")
    } catch (rejected: MarketModerationRejected) {
        operationResult(MarketOperationResult.Status.REJECTED, null, rejected.message ?: "Market operation rejected")
    }

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
    }

    private companion object {
        const val MAXIMUM_STALLS_PER_PLAYER = 100
    }
}
