package net.badgersmc.em.infrastructure.persistence

import java.sql.Connection
import java.util.UUID

/** Distinguishes a missing shop from a mutation rejected by a moderation fence. */
internal object ShopModerationFenceQueries {
    fun rejectLockedShop(connection: Connection, shopId: Long, updateCount: Int) {
        if (updateCount != 0) return
        connection.prepareStatement(
            """SELECT 1 FROM shop_items s
               JOIN market_moderation_locks l ON l.stall_id = s.stall_id
               WHERE s.id = ?""",
        ).use { statement ->
            statement.setLong(1, shopId)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    throw MarketModerationConflictException("Shop $shopId is reserved for moderation")
                }
            }
        }
    }

    fun rejectLockedStall(connection: Connection, stallId: String) {
        connection.prepareStatement(
            "SELECT 1 FROM market_moderation_locks WHERE stall_id = ?",
        ).use { statement ->
            statement.setString(1, stallId)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    throw MarketModerationConflictException("Stall $stallId is reserved for moderation")
                }
            }
        }
    }

    fun rejectLockedContainer(
        connection: Connection,
        world: String,
        x: Int,
        y: Int,
        z: Int,
    ) {
        connection.prepareStatement(
            """SELECT 1 FROM shop_items s
               JOIN market_moderation_locks l ON l.stall_id = s.stall_id
               WHERE s.container_world = ? AND s.container_x = ?
                 AND s.container_y = ? AND s.container_z = ?""",
        ).use { statement ->
            statement.setString(1, world)
            statement.setInt(2, x)
            statement.setInt(3, y)
            statement.setInt(4, z)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    throw MarketModerationConflictException("Container shop is reserved for moderation")
                }
            }
        }
    }

    fun rejectLockedOwner(connection: Connection, owner: UUID) {
        connection.prepareStatement(
            """SELECT 1 FROM shop_items s
               JOIN market_moderation_locks l ON l.stall_id = s.stall_id
               WHERE s.owner = ?""",
        ).use { statement ->
            statement.setString(1, owner.toString())
            statement.executeQuery().use { result ->
                if (result.next()) {
                    throw MarketModerationConflictException("An owned shop is reserved for moderation")
                }
            }
        }
    }
}
