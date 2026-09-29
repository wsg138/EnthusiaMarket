package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID
import javax.sql.DataSource

/** JDBC implementation of [ShopRepository]. */
class ShopRepositorySql(private val ds: DataSource) : ShopRepository {
    override fun findById(id: Long): Shop? = ds.connection.use { conn ->
        conn.prepareStatement("SELECT * FROM shop_items WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
        }
    }

    override fun findByStall(stallId: String): List<Shop> = ds.connection.use { conn ->
        conn.prepareStatement("SELECT * FROM shop_items WHERE stall_id = ? ORDER BY id").use { ps ->
            ps.setString(1, stallId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    override fun findByContainer(world: String, x: Int, y: Int, z: Int): Shop? = ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT * FROM shop_items WHERE container_world = ? AND container_x = ? AND container_y = ? AND container_z = ?"
        ).use { ps ->
            ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z)
            ps.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
        }
    }

    override fun findBySign(world: String, x: Int, y: Int, z: Int): Shop? = ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT * FROM shop_items WHERE sign_world = ? AND sign_x = ? AND sign_y = ? AND sign_z = ?"
        ).use { ps ->
            ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z)
            ps.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
        }
    }

    override fun upsert(shop: Shop): Shop {
        return ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                val saved = if (shop.id == 0L) insert(conn, shop) else update(conn, shop)
                conn.commit()
                saved
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun delete(id: Long): Boolean {
        return ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                ShopModerationFenceQueries.lockShopForMutation(conn, id)
                conn.prepareStatement("DELETE FROM shop_transactions WHERE shop_id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeUpdate()
                }
                val deleted = conn.prepareStatement(
                    """DELETE FROM shop_items WHERE id = ?
                       AND NOT EXISTS (
                           SELECT 1 FROM market_moderation_locks l
                           WHERE l.stall_id = shop_items.stall_id
                       )"""
                ).use { ps ->
                    ps.setLong(1, id)
                    ps.executeUpdate()
                }
                ShopModerationFenceQueries.rejectLockedShop(conn, id, deleted)
                conn.commit()
                deleted > 0
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun deleteByContainer(world: String, x: Int, y: Int, z: Int): Boolean {
        return ds.connection.use { conn ->
            conn.autoCommit = false
            val position = ShopModerationFenceQueries.ContainerPosition(world, x, y, z)
            try {
                ShopModerationFenceQueries.lockContainerForMutation(conn, world, x, y, z)
                conn.prepareStatement(
                    """DELETE FROM shop_transactions WHERE shop_id IN
                       (SELECT id FROM shop_items
                        WHERE container_world = ? AND container_x = ? AND container_y = ? AND container_z = ?)"""
                ).use { ps ->
                    ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z)
                    ps.executeUpdate()
                }
                val deleted = conn.prepareStatement(
                    """DELETE FROM shop_items
                       WHERE container_world = ? AND container_x = ? AND container_y = ? AND container_z = ?
                         AND NOT EXISTS (
                             SELECT 1 FROM market_moderation_locks l
                             WHERE l.stall_id = shop_items.stall_id
                         )"""
                ).use { ps ->
                    ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z)
                    ps.executeUpdate()
                }
                ShopModerationFenceQueries.rejectLockedContainer(conn, position)
                conn.commit()
                deleted > 0
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun deleteByOwner(owner: UUID): Int {
        return ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                ShopModerationFenceQueries.lockOwnerForMutation(conn, owner)
                conn.prepareStatement(
                    """DELETE FROM shop_transactions WHERE shop_id IN
                       (SELECT id FROM shop_items WHERE owner = ?)"""
                ).use { ps ->
                    ps.setString(1, owner.toString())
                    ps.executeUpdate()
                }
                val deleted = conn.prepareStatement(
                    """DELETE FROM shop_items WHERE owner = ?
                       AND NOT EXISTS (
                           SELECT 1 FROM market_moderation_locks l
                           WHERE l.stall_id = shop_items.stall_id
                       )"""
                ).use { ps ->
                    ps.setString(1, owner.toString())
                    ps.executeUpdate()
                }
                ShopModerationFenceQueries.rejectLockedOwner(conn, owner)
                conn.commit()
                deleted
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun setFrozen(id: Long, frozen: Boolean): Boolean {
        return ds.connection.use { conn ->
            conn.prepareStatement(
                """UPDATE shop_items SET frozen = ? WHERE id = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM market_moderation_locks l
                       WHERE l.stall_id = shop_items.stall_id
                   )"""
            ).use { ps ->
                ps.setBoolean(1, frozen)
                ps.setLong(2, id)
                val updated = ps.executeUpdate()
                ShopModerationFenceQueries.rejectLockedShop(conn, id, updated)
                updated > 0
            }
        }
    }

    override fun updateStock(id: Long, stockCount: Int): Boolean {
        return ds.connection.use { conn ->
            conn.prepareStatement(
                """UPDATE shop_items SET stock_count = ? WHERE id = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM market_moderation_locks l
                       WHERE l.stall_id = shop_items.stall_id
                   )"""
            ).use { ps ->
                ps.setInt(1, stockCount)
                ps.setLong(2, id)
                val updated = ps.executeUpdate()
                ShopModerationFenceQueries.rejectLockedShop(conn, id, updated)
                updated > 0
            }
        }
    }

    override fun updateStockBatch(stocks: Map<Long, Int>) {
        if (stocks.isEmpty()) return
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                stocks.forEach { (id, stock) ->
                    conn.prepareStatement(
                        """UPDATE shop_items SET stock_count = ? WHERE id = ?
                           AND NOT EXISTS (
                               SELECT 1 FROM market_moderation_locks l
                               WHERE l.stall_id = shop_items.stall_id
                           )"""
                    ).use { ps ->
                        ps.setInt(1, stock)
                        ps.setLong(2, id)
                        ps.executeUpdate()
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun all(): List<Shop> = ds.connection.use { conn ->
        conn.prepareStatement("SELECT * FROM shop_items ORDER BY id").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    private fun insert(conn: Connection, shop: Shop): Shop {
        ShopModerationFenceQueries.rejectLockedStall(conn, shop.stallId)
        val sql = """INSERT INTO shop_items
            (stall_id, owner, sign_world, sign_x, sign_y, sign_z,
             container_world, container_x, container_y, container_z,
             sell_item, sell_amount, cost_item, cost_amount,
             trusted, hopper_allow_in, hopper_allow_out, frozen, admin_shop,
             direction, search_enabled, sell_material, stock_count)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            WHERE NOT EXISTS (
                SELECT 1 FROM market_moderation_locks WHERE stall_id = ?
            )"""
        conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
            bindShop(ps, shop)
            ps.setString(24, shop.stallId)
            if (ps.executeUpdate() != 1) {
                ShopModerationFenceQueries.rejectLockedStall(conn, shop.stallId)
                throw IllegalStateException("Shop insert failed for stall ${shop.stallId}")
            }
            ps.generatedKeys.use { keys ->
                if (!keys.next()) error("No generated key for shop insert")
                return shop.copy(id = keys.getLong(1))
            }
        }
    }

    private fun update(conn: Connection, shop: Shop): Shop {
        ShopModerationFenceQueries.lockShopForMutation(conn, shop.id)
        val sql = """UPDATE shop_items SET
            stall_id = ?, owner = ?, sign_world = ?, sign_x = ?, sign_y = ?, sign_z = ?,
            container_world = ?, container_x = ?, container_y = ?, container_z = ?,
            sell_item = ?, sell_amount = ?, cost_item = ?, cost_amount = ?, trusted = ?,
            hopper_allow_in = ?, hopper_allow_out = ?, frozen = ?, admin_shop = ?,
            direction = ?, search_enabled = ?, sell_material = ?, stock_count = ?
            WHERE id = ? AND NOT EXISTS (
                SELECT 1 FROM market_moderation_locks l
                WHERE l.stall_id = shop_items.stall_id
            )"""
        conn.prepareStatement(sql).use { ps ->
            bindShop(ps, shop)
            ps.setLong(24, shop.id)
            val updated = ps.executeUpdate()
            ShopModerationFenceQueries.rejectLockedShop(conn, shop.id, updated)
            if (updated != 1) error("Shop update failed for id=${shop.id}")
        }
        return shop
    }

    private fun bindShop(ps: java.sql.PreparedStatement, shop: Shop) {
        ps.setString(1, shop.stallId)
        ps.setString(2, shop.owner.toString())
        ps.setString(3, shop.signWorld)
        ps.setInt(4, shop.signX)
        ps.setInt(5, shop.signY)
        ps.setInt(6, shop.signZ)
        ps.setString(7, shop.containerWorld)
        ps.setInt(8, shop.containerX)
        ps.setInt(9, shop.containerY)
        ps.setInt(10, shop.containerZ)
        ps.setString(11, shop.sellItem)
        ps.setInt(12, shop.sellAmount)
        ps.setString(13, shop.costItem)
        ps.setInt(14, shop.costAmount)
        ps.setString(15, shop.trusted.joinToString(","))
        ps.setBoolean(16, shop.hopperAllowIn)
        ps.setBoolean(17, shop.hopperAllowOut)
        ps.setBoolean(18, shop.frozen)
        ps.setBoolean(19, shop.adminShop)
        ps.setString(20, shop.direction.name)
        ps.setBoolean(21, shop.searchEnabled)
        ps.setString(22, shop.sellMaterial)
        ps.setInt(23, shop.stockCount)
    }

    private fun map(rs: ResultSet): Shop = Shop(
        id = rs.getLong("id"),
        stallId = rs.getString("stall_id"),
        owner = UUID.fromString(rs.getString("owner")),
        signWorld = rs.getString("sign_world"),
        signX = rs.getInt("sign_x"),
        signY = rs.getInt("sign_y"),
        signZ = rs.getInt("sign_z"),
        containerWorld = rs.getString("container_world"),
        containerX = rs.getInt("container_x"),
        containerY = rs.getInt("container_y"),
        containerZ = rs.getInt("container_z"),
        sellItem = rs.getString("sell_item"),
        sellAmount = rs.getInt("sell_amount"),
        costItem = rs.getString("cost_item"),
        costAmount = rs.getInt("cost_amount"),
        trusted = rs.getString("trusted").split(',').filter(String::isNotBlank).map(UUID::fromString).toSet(),
        hopperAllowIn = rs.getBoolean("hopper_allow_in"),
        hopperAllowOut = rs.getBoolean("hopper_allow_out"),
        frozen = rs.getBoolean("frozen"),
        adminShop = rs.getBoolean("admin_shop"),
        direction = SignDirection.valueOf(rs.getString("direction")),
        searchEnabled = rs.getBoolean("search_enabled"),
        sellMaterial = rs.getString("sell_material"),
        stockCount = rs.getInt("stock_count"),
    )
}
