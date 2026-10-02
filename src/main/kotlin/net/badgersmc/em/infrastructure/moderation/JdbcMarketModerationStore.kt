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
import java.time.Clock
import java.util.Optional
import java.util.UUID
import javax.sql.DataSource

/**
 * Durable implementation of the Market side of the Staff moderation contract.
 *
 * Validation and orchestration live here while SQL binding/persistence details
 * live in [MarketModerationStoreSql] helpers.
 */
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
                    while (result.next()) add(result.toMarketStallRecord())
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
            ?: return operationResult(MarketOperationResult.Status.REJECTED, null, "Market operation does not exist")
        confiscationPrecondition(operation, approval)?.let { return it }
        if (verifiedOriginal(operation) == null) {
            return quarantine(connection, operation, "Stored market snapshot failed its integrity check")
        }
        val current = snapshotCodec.capture(connection, operation.stallId, operation.targetId)
        if (current.checksum != operation.currentChecksum) {
            return quarantine(connection, operation, "Prepared market state changed before review")
        }
        connection.holdMarketStall(operation, current.stallRevision)
        val held = snapshotCodec.capture(connection, operation.stallId, operation.targetId)
        val updated = connection.updateMarketOperation(operation, confiscationUpdate(approval, held.checksum))
        return operationResult(MarketOperationResult.Status.HELD, updated, updated.detail)
    }

    private fun confiscationPrecondition(
        operation: MarketOperationRow,
        approval: MarketConfiscationApproval,
    ): MarketOperationResult? = when {
        operation.state == MarketOperationRecord.State.MODERATION_HOLD ->
            operationResult(MarketOperationResult.Status.REPLAYED, operation, "Market confiscation was already reviewed")
        operation.state != MarketOperationRecord.State.PREPARED ->
            conflict(operation, "Only a prepared market operation can be confiscated")
        operation.snapshotChecksum != approval.expectedSnapshotChecksum() ->
            conflict(operation, "Prepared snapshot checksum does not match")
        else -> null
    }

    private fun confiscationUpdate(approval: MarketConfiscationApproval, checksum: String) = OperationUpdate(
        state = MarketOperationRecord.State.MODERATION_HOLD,
        currentChecksum = checksum,
        reviewerId = approval.reviewerId(),
        detail = "Ownership placed in a reviewed moderation hold",
        updatedAt = approval.reviewedAt().toEpochMilli(),
    )

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
            ?: return@moderatedTransaction quarantine(connection, operation, "Stored market snapshot failed its integrity check")
        val current = snapshotCodec.capture(connection, operation.stallId, operation.targetId)
        if (current.checksum != operation.currentChecksum) {
            return@moderatedTransaction quarantine(connection, operation, "Held market state changed before restoration")
        }

        restoreOriginal(connection, operation, original, current.stallRevision)
        releaseReservations(connection, operation)
        val updated = connection.updateMarketOperation(
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
                return@moderatedTransaction quarantine(connection, operation, "Prepared market state changed before release")
            }

            restoreOriginal(connection, operation, original, current.stallRevision)
            releaseReservations(connection, operation)
            val updated = connection.updateMarketOperation(
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
        connection.lockMarketSnapshotRows(request.stallId())
        connection.findMarketOperation(request.operationId())?.let { existing ->
            if (!existing.matches(request)) {
                throw MarketModerationConflict("Operation id belongs to a different market request")
            }
            return operationResult(MarketOperationResult.Status.REPLAYED, existing, "Market operation already exists")
        }

        connection.reserveMarketStall(request.stallId(), request.operationId(), clock.millis())
        val original = snapshotCodec.capture(connection, request.stallId(), request.targetId())
        requireTargetOwnership(original, request.targetId())
        restrictions.reservePlayer(connection, request.targetId(), request.operationId())
        connection.advanceMarketStallRevision(request.stallId(), original.stallRevision)
        connection.freezeMarketShops(request.stallId())
        restrictions.applyPreparedBlacklist(connection, request)
        val prepared = snapshotCodec.capture(connection, request.stallId(), request.targetId())
        val insert = PreparedOperationInsert(request, original, prepared.checksum, clock.millis())
        connection.insertMarketOperation(insert)
        val operation = checkNotNull(connection.findMarketOperation(request.operationId()))
        return operationResult(MarketOperationResult.Status.PREPARED, operation, operation.detail)
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
        connection.restoreMarketStall(original.stall, expectedRevision)
        connection.restoreMarketShopFlags(original.shops)
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
        val updated = connection.updateMarketOperation(
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

    private companion object {
        const val MAXIMUM_STALLS_PER_PLAYER = 100
    }
}
