package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketBlacklistRemoval
import net.enthusia.market.api.moderation.MarketBlacklistRequest
import net.enthusia.market.api.moderation.MarketBlacklistResult
import net.enthusia.market.api.moderation.MarketOperationRequest
import net.enthusia.market.api.moderation.StallBlacklistState
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.util.Optional
import java.util.UUID
import javax.sql.DataSource

/** Owns player acquisition fences and case-linked Market blacklist policy. */
internal class MarketRestrictionJournal(
    private val dataSource: DataSource,
    private val clock: Clock,
) {
    fun getBlacklist(playerId: UUID): Optional<StallBlacklistState> = dataSource.connection.use { connection ->
        Optional.ofNullable(connection.readMarketBlacklist(playerId))
    }

    fun canAcquire(playerId: UUID): Boolean = dataSource.connection.use { connection ->
        val now = clock.millis()
        !connection.hasActiveMarketBlacklist(playerId, now) &&
            !connection.hasActiveMarketPlayerFence(playerId, now)
    }

    fun apply(request: MarketBlacklistRequest): MarketBlacklistResult =
        dataSource.marketBlacklistTransaction { connection -> apply(connection, request) }

    fun remove(removal: MarketBlacklistRemoval): MarketBlacklistResult =
        dataSource.marketBlacklistTransaction { connection -> remove(connection, removal) }

    private fun remove(connection: Connection, removal: MarketBlacklistRemoval): MarketBlacklistResult {
        claimRestrictionMutation(connection, removal.targetId())
        val current = connection.readMarketBlacklist(removal.targetId())
        removalPrecondition(current, removal)?.let { return it }
        if (!connection.markMarketBlacklistRemoved(removal, clock.millis())) {
            return marketBlacklistResult(
                MarketBlacklistResult.Status.CONFLICT,
                connection.readMarketBlacklist(removal.targetId()),
                "Market blacklist changed concurrently",
            )
        }
        val removed = checkNotNull(connection.readMarketBlacklist(removal.targetId()))
        return marketBlacklistResult(MarketBlacklistResult.Status.REMOVED, removed, "Market blacklist removed")
    }

    private fun removalPrecondition(
        current: StallBlacklistState?,
        removal: MarketBlacklistRemoval,
    ): MarketBlacklistResult? = when {
        current == null -> marketBlacklistResult(
            MarketBlacklistResult.Status.REJECTED,
            null,
            "Player does not have a market blacklist record",
        )
        current.operationId() == removal.operationId() && current.status() == StallBlacklistState.Status.REMOVED ->
            marketBlacklistResult(MarketBlacklistResult.Status.REPLAYED, current, "Market blacklist was already removed")
        current.caseId() != removal.caseId() || current.revision() != removal.expectedRevision() ->
            marketBlacklistResult(MarketBlacklistResult.Status.CONFLICT, current, "Market blacklist case or revision changed")
        else -> null
    }

    fun reservePlayer(connection: Connection, playerId: UUID, operationId: UUID) {
        val now = clock.millis()
        claimPlayerFence {
            PlayerFenceClaims.claimModeration(connection, playerId, operationId, now)
        }
    }

    fun applyPreparedBlacklist(connection: Connection, request: MarketOperationRequest) {
        val current = connection.readMarketBlacklist(request.targetId())
        if (current?.activeAt(clock.instant()) == true) return
        connection.writeMarketBlacklist(preparedBlacklistWrite(request, current))
    }

    private fun preparedBlacklistWrite(
        request: MarketOperationRequest,
        current: StallBlacklistState?,
    ) = BlacklistWrite(
        request.operationId(),
        request.targetId(),
        request.caseId(),
        request.blacklistExpiresAt().orElse(null)?.toEpochMilli(),
        (current?.revision() ?: 0L) + 1L,
        clock.millis(),
    )

    fun restoreBlacklist(
        connection: Connection,
        operation: MarketOperationRow,
        original: ModeratedBlacklistSnapshot?,
    ) {
        val current = connection.readMarketBlacklist(operation.targetId)
        if (original == null) {
            restoreAbsentBlacklist(connection, operation, current)
            return
        }
        if (current == null) throw MarketModerationConflict("Market blacklist is missing during restoration")
        if (current.matches(original)) return
        requireCurrentBlacklistOperation(current, operation.operationId)
        connection.writeMarketBlacklistSnapshot(original, current.revision())
    }

    fun releasePlayerReservation(connection: Connection, operation: MarketOperationRow) {
        if (!connection.releaseMarketPlayerReservation(operation, clock.millis())) {
            throw MarketModerationConflict("Market player reservation is missing")
        }
    }

    private fun restoreAbsentBlacklist(
        connection: Connection,
        operation: MarketOperationRow,
        current: StallBlacklistState?,
    ) {
        if (current == null) return
        requireCurrentBlacklistOperation(current, operation.operationId)
        if (!connection.deleteRestoredBlacklist(operation, current)) {
            throw MarketModerationConflict("Market blacklist changed concurrently")
        }
    }

    private fun requireCurrentBlacklistOperation(current: StallBlacklistState, operationId: UUID) {
        if (current.operationId() != operationId) {
            throw MarketModerationConflict("A newer market blacklist prevents restoration")
        }
    }

    private fun apply(connection: Connection, request: MarketBlacklistRequest): MarketBlacklistResult {
        claimRestrictionMutation(connection, request.targetId())
        val current = connection.readMarketBlacklist(request.targetId())
        replay(current, request)?.let { return it }
        if (current?.activeAt(clock.instant()) == true) {
            throw MarketModerationConflict("Player already has an active market blacklist")
        }
        connection.writeMarketBlacklist(blacklistWrite(request, current))
        val applied = checkNotNull(connection.readMarketBlacklist(request.targetId()))
        return marketBlacklistResult(MarketBlacklistResult.Status.APPLIED, applied, "Market blacklist applied")
    }

    private fun blacklistWrite(
        request: MarketBlacklistRequest,
        current: StallBlacklistState?,
    ) = BlacklistWrite(
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
        return marketBlacklistResult(MarketBlacklistResult.Status.REPLAYED, current, "Market blacklist already applied")
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

    private fun StallBlacklistState.matches(snapshot: ModeratedBlacklistSnapshot): Boolean =
        playerId().toString() == snapshot.playerId &&
            status().name == snapshot.status &&
            expiresAt().orElse(null)?.toEpochMilli() == snapshot.expiresAt &&
            caseId() == snapshot.caseId &&
            operationId().toString() == snapshot.operationId &&
            revision() == snapshot.revision &&
            updatedAt().toEpochMilli() == snapshot.updatedAt


}
