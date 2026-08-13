package net.badgersmc.em.infrastructure.moderation

import net.badgersmc.em.domain.ports.MarketAcquisitionBlockedException
import net.badgersmc.em.domain.ports.MarketModerationPolicy
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** Database-backed acquisition fence shared by purchases, auctions, and Staff operations. */
internal class JdbcMarketModerationPolicy(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : MarketModerationPolicy {
    override fun <T> withAcquisitionPermit(playerId: UUID, action: () -> T): T {
        val permitId = "acquisition:${UUID.randomUUID()}"
        acquire(playerId, permitId)
        val outcome = runCatching(action)
        val releaseFailure = runCatching { release(playerId, permitId) }.exceptionOrNull()
        return completedAction(outcome, releaseFailure)
    }

    private fun <T> completedAction(outcome: Result<T>, releaseFailure: Throwable?): T {
        val actionFailure = outcome.exceptionOrNull()
        if (actionFailure != null) {
            releaseFailure?.let(actionFailure::addSuppressed)
            throw actionFailure
        }
        if (releaseFailure != null) throw releaseFailure
        return outcome.getOrThrow()
    }

    override fun canAcquire(playerId: UUID): Boolean = dataSource.connection.use { connection ->
        val now = clock.millis()
        !activeBlacklist(connection, playerId, now) && !activeFence(connection, playerId, now)
    }

    private fun acquire(playerId: UUID, permitId: String) {
        try {
            dataSource.inTransaction { connection ->
                val now = clock.millis()
                val fence = readFence(connection, playerId)
                ensureAvailable(connection, playerId, fence, now)
                if (fence == null) insertFence(connection, playerId, permitId, now)
                else updateFence(connection, playerId, permitId, fence.revision, now)
            }
        } catch (failure: SQLException) {
            throw failure.asAcquisitionFailure()
        }
    }

    private fun ensureAvailable(connection: Connection, playerId: UUID, fence: Fence?, now: Long) {
        val rejection = when {
            activeBlacklist(connection, playerId, now) -> "Market acquisitions are restricted for this player"
            fence?.activeAt(now) == true -> "Another market acquisition or review is in progress"
            else -> null
        }
        if (rejection != null) throw MarketAcquisitionBlockedException(rejection)
    }

    private fun release(playerId: UUID, permitId: String) {
        dataSource.inTransaction { connection ->
            connection.prepareStatement(
                """UPDATE market_player_fences
                   SET active_acquisition_id = NULL, acquisition_until = NULL,
                       revision = revision + 1, updated_at = ?
                   WHERE player_uuid = ? AND active_acquisition_id = ?""",
            ).use { statement ->
                statement.setLong(1, clock.millis())
                statement.setString(2, playerId.toString())
                statement.setString(3, permitId)
                statement.executeUpdate()
            }
        }
    }

    private fun insertFence(connection: Connection, playerId: UUID, permitId: String, now: Long) {
        connection.prepareStatement(
            """INSERT INTO market_player_fences
               (player_uuid, active_acquisition_id, acquisition_until, revision, updated_at)
               VALUES (?, ?, ?, 1, ?)""",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, permitId)
            statement.setLong(3, now + PERMIT_DURATION.toMillis())
            statement.setLong(4, now)
            statement.executeUpdate()
        }
    }

    private fun updateFence(
        connection: Connection,
        playerId: UUID,
        permitId: String,
        expectedRevision: Long,
        now: Long,
    ) {
        connection.prepareStatement(
            """UPDATE market_player_fences
               SET active_acquisition_id = ?, acquisition_until = ?,
                   revision = revision + 1, updated_at = ?
               WHERE player_uuid = ? AND revision = ?""",
        ).use { statement ->
            statement.setString(1, permitId)
            statement.setLong(2, now + PERMIT_DURATION.toMillis())
            statement.setLong(3, now)
            statement.setString(4, playerId.toString())
            statement.setLong(5, expectedRevision)
            if (statement.executeUpdate() != 1) {
                throw MarketAcquisitionBlockedException("Market acquisition fence changed concurrently")
            }
        }
    }

    private fun activeBlacklist(connection: Connection, playerId: UUID, now: Long): Boolean =
        connection.prepareStatement(
            """SELECT 1 FROM market_stall_blacklists
               WHERE player_uuid = ? AND status = 'ACTIVE'
                 AND (expires_at IS NULL OR expires_at > ?)""",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setLong(2, now)
            statement.executeQuery().use { it.next() }
        }

    private fun activeFence(connection: Connection, playerId: UUID, now: Long): Boolean =
        readFence(connection, playerId)?.activeAt(now) == true

    private fun readFence(connection: Connection, playerId: UUID): Fence? =
        connection.prepareStatement(
            "SELECT active_acquisition_id, acquisition_until, revision FROM market_player_fences WHERE player_uuid = ?",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                val until = result.getLong("acquisition_until").takeUnless { result.wasNull() }
                Fence(result.getString("active_acquisition_id"), until, result.getLong("revision"))
            }
        }

    private fun SQLException.isConstraintViolation(): Boolean =
        sqlState?.startsWith("23") == true ||
            message.orEmpty().contains("constraint", ignoreCase = true) ||
            message.orEmpty().contains("unique", ignoreCase = true)

    private fun SQLException.asAcquisitionFailure(): Exception =
        if (isConstraintViolation() || sqlState == "40001") {
            MarketAcquisitionBlockedException("Market acquisition fence changed concurrently")
        } else {
            this
        }

    private data class Fence(val id: String?, val until: Long?, val revision: Long) {
        fun activeAt(now: Long): Boolean = id != null && (until == null || until > now)
    }

    private companion object {
        val PERMIT_DURATION: Duration = Duration.ofMinutes(2)
    }
}
