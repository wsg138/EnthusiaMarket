package net.badgersmc.em.infrastructure.moderation

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Atomic claims on the row shared by acquisitions and moderation mutations. */
internal object PlayerFenceClaims {
    fun claimAcquisition(
        connection: Connection,
        playerId: UUID,
        permitId: String,
        permitUntil: Long,
        now: Long,
    ): Boolean {
        ensureRow(connection, playerId, now)
        return connection.prepareStatement(
            """UPDATE market_player_fences
               SET active_acquisition_id = ?, acquisition_until = ?,
                   revision = revision + 1, updated_at = ?
               WHERE player_uuid = ?
                 AND (active_acquisition_id IS NULL
                      OR (acquisition_until IS NOT NULL AND acquisition_until <= ?))
                 AND NOT EXISTS (
                     SELECT 1 FROM market_stall_blacklists
                     WHERE player_uuid = ? AND status = 'ACTIVE'
                       AND (expires_at IS NULL OR expires_at > ?)
                 )""",
        ).use { statement ->
            statement.setString(1, permitId)
            statement.setLong(2, permitUntil)
            statement.setLong(3, now)
            statement.setString(4, playerId.toString())
            statement.setLong(5, now)
            statement.setString(6, playerId.toString())
            statement.setLong(7, now)
            statement.executeUpdate() == 1
        }
    }

    fun claimModeration(
        connection: Connection,
        playerId: UUID,
        operationId: UUID,
        now: Long,
    ): Boolean {
        ensureRow(connection, playerId, now)
        return connection.prepareStatement(
            """UPDATE market_player_fences
               SET active_acquisition_id = ?, acquisition_until = NULL,
                   revision = revision + 1, updated_at = ?
               WHERE player_uuid = ?
                 AND (active_acquisition_id IS NULL
                      OR (acquisition_until IS NOT NULL AND acquisition_until <= ?))""",
        ).use { statement ->
            statement.setString(1, "moderation:$operationId")
            statement.setLong(2, now)
            statement.setString(3, playerId.toString())
            statement.setLong(4, now)
            statement.executeUpdate() == 1
        }
    }

    fun claimRestrictionMutation(
        connection: Connection,
        playerId: UUID,
        now: Long,
    ): Boolean {
        ensureRow(connection, playerId, now)
        return connection.prepareStatement(
            """UPDATE market_player_fences
               SET active_acquisition_id = NULL, acquisition_until = NULL,
                   revision = revision + 1, updated_at = ?
               WHERE player_uuid = ?
                 AND (active_acquisition_id IS NULL
                      OR (acquisition_until IS NOT NULL AND acquisition_until <= ?))""",
        ).use { statement ->
            statement.setLong(1, now)
            statement.setString(2, playerId.toString())
            statement.setLong(3, now)
            statement.executeUpdate() == 1
        }
    }

    private fun ensureRow(connection: Connection, playerId: UUID, now: Long) {
        try {
            connection.prepareStatement(
                """INSERT INTO market_player_fences
                   (player_uuid, active_acquisition_id, acquisition_until, revision, updated_at)
                   VALUES (?, NULL, NULL, 0, ?)""",
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setLong(2, now)
                statement.executeUpdate()
            }
        } catch (failure: SQLException) {
            if (!failure.isDuplicateKeyViolation()) throw failure
        }
    }

}
