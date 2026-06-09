package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.annotations.Repository
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID
import javax.sql.DataSource

@Repository
@Suppress("TooManyFunctions")
class ShopRepositorySql(private val ds: DataSource) : ShopRepository {

    override fun upsert(shop: Shop): Shop {
        if (shop.id == 0L) {
            return insert(shop)
        } else {
            update(shop)
            return shop
        }
    }

    override fun findById(id: Long): Shop? = queryOne("SELECT * FROM shop_items WHERE id = ?") {
        setLong(1, id)
    }

    override fun findBySign(world: String, x: Int, y: Int, z: Int): Shop? =
        queryOne("SELECT * FROM shop_items WHERE sign_world = ? AND sign_x = ? AND sign_y = ? AND sign_z = ?") {
            setString(1, world); setInt(2, x); setInt(3, y); setInt(4, z)
        }

    override fun findByContainer(world: String, x: Int, y: Int, z: Int): List<Shop> =
        queryMany("SELECT * FROM shop_items WHERE container_world = ? AND container_x = ? AND container_y = ? AND container_z = ?") {
            setString(1, world); setInt(2, x); setInt(3, y); setInt(4, z)
        }

    override fun findByStall(stallId: String): List<Shop> =
        queryMany("SELECT * FROM shop_items WHERE stall_id = ?") {
            setString(1, stallId)
        }

    override fun findByOwner(owner: UUID): List<Shop> =
        queryMany("SELECT * FROM shop_items WHERE owner = ?") {
            setString(1, owner.toString())
        }

    override fun findByGuildId(guildId: UUID): List<Shop> =
        queryMany("SELECT * FROM shop_items WHERE guild_id = ?") {
            setString(1, guildId.toString())
        }

    override fun setGuildOwnership(id: Long, guildId: UUID, creatorId: UUID): Shop? {
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE shop_items SET guild_id = ?, creator_id = ? WHERE id = ?").use { ps ->
                ps.setString(1, guildId.toString())
                ps.setString(2, creatorId.toString())
                ps.setLong(3, id)
                ps.executeUpdate()
            }
        }
        return findById(id)
    }

    override fun removeGuildOwnership(id: Long): Shop? {
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE shop_items SET guild_id = NULL, creator_id = NULL WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
        return findById(id)
    }

    override fun all(): List<Shop> = queryMany("SELECT * FROM shop_items") {}

    override fun countAll(): Int = count("SELECT COUNT(*) FROM shop_items") {}

    override fun countByOwner(owner: UUID): Int =
        count("SELECT COUNT(*) FROM shop_items WHERE owner = ?") { it.setString(1, owner.toString()) }

    private inline fun count(sql: String, bind: (java.sql.PreparedStatement) -> Unit): Int {
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps)
                ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    override fun delete(id: Long) {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM shop_items WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
    }

    override fun deleteByContainer(world: String, x: Int, y: Int, z: Int) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM shop_items WHERE container_world = ? AND container_x = ? AND container_y = ? AND container_z = ?"
            ).use { ps ->
                ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z)
                ps.executeUpdate()
            }
        }
    }

    override fun deleteByOwner(owner: UUID): Int {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM shop_items WHERE owner = ?").use { ps ->
                ps.setString(1, owner.toString())
                return ps.executeUpdate()
            }
        }
    }

    override fun findBySellMaterial(material: String): List<Shop> =
        queryMany("SELECT * FROM shop_items WHERE sell_material = ? AND search_enabled = 1") {
            setString(1, material)
        }

    override fun backfillSellMaterials(): Int {
        // Read only id + sell_item so a malformed legacy row (bad trusted/UUID/direction)
        // can't abort the backfill via mapRow's strict parsing.
        val rows = mutableListOf<Pair<Long, String>>()
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT id, sell_item FROM shop_items WHERE sell_material IS NULL").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) rows.add(rs.getLong("id") to rs.getString("sell_item"))
                }
            }
            // `AND sell_material IS NULL` keeps the batch idempotent: between the
            // SELECT and this UPDATE another writer may have set sell_material, so
            // we must not clobber a fresher value with our stale deserialization.
            conn.prepareStatement(
                "UPDATE shop_items SET sell_material = ? WHERE id = ? AND sell_material IS NULL"
            ).use { ps ->
                for ((id, sellItem) in rows) {
                    val mat = net.badgersmc.em.application.ItemStackSerializer.deserialize(sellItem)?.type?.name ?: continue
                    ps.setString(1, mat); ps.setLong(2, id); ps.addBatch()
                }
                return ps.executeBatch().sumOf { count ->
                    when (count) {
                        Statement.SUCCESS_NO_INFO -> 1
                        Statement.EXECUTE_FAILED -> 0
                        else -> maxOf(count, 0)
                    }
                }
            }
        }
    }

    private fun insert(shop: Shop): Shop {
        ds.connection.use { conn ->
            val sql = """
                INSERT INTO shop_items
                (stall_id, owner, sign_world, sign_x, sign_y, sign_z,
                 container_world, container_x, container_y, container_z,
                 sell_item, sell_amount, cost_item, cost_amount,
                 trusted, hopper_allow_in, hopper_allow_out, frozen, admin_shop,
                 guild_id, creator_id, direction, search_enabled, sell_material, stock_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                bind(ps, shop)
                ps.executeUpdate()
                ps.generatedKeys.use { keys ->
                    if (keys.next()) {
                        return shop.copy(id = keys.getLong(1))
                    } else {
                        error("ShopRepositorySql.insert: no generated key returned")
                    }
                }
            }
        }
    }

    private fun update(shop: Shop) {
        ds.connection.use { conn ->
            conn.prepareStatement(UPDATE_SQL).use { ps ->
                bind(ps, shop)
                ps.setLong(26, shop.id)
                ps.executeUpdate()
            }
        }
    }

    private companion object {
        private val UPDATE_SQL = """
            UPDATE shop_items SET
              stall_id = ?, owner = ?, sign_world = ?, sign_x = ?, sign_y = ?, sign_z = ?,
              container_world = ?, container_x = ?, container_y = ?, container_z = ?,
              sell_item = ?, sell_amount = ?, cost_item = ?, cost_amount = ?,
              trusted = ?, hopper_allow_in = ?, hopper_allow_out = ?, frozen = ?, admin_shop = ?,
              guild_id = ?, creator_id = ?, direction = ?, search_enabled = ?, sell_material = ?, stock_count = ?
            WHERE id = ?
        """.trimIndent()
    }

    override fun updateStock(id: Long, stockCount: Int) {
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE shop_items SET stock_count = ? WHERE id = ?").use { ps ->
                ps.setInt(1, stockCount)
                ps.setLong(2, id)
                ps.executeUpdate()
            }
        }
    }

    private fun sellMaterialOf(shop: Shop): String? =
        net.badgersmc.em.application.ItemStackSerializer.deserialize(shop.sellItem)?.type?.name

    private fun bind(ps: PreparedStatement, shop: Shop) {
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
        if (shop.guildId != null) ps.setString(20, shop.guildId.toString()) else ps.setNull(20, java.sql.Types.VARCHAR)
        if (shop.creatorId != null) ps.setString(21, shop.creatorId.toString()) else ps.setNull(21, java.sql.Types.VARCHAR)
        ps.setString(22, shop.direction.name)
        ps.setBoolean(23, shop.searchEnabled)
        val mat = sellMaterialOf(shop)
        if (mat != null) ps.setString(24, mat) else ps.setNull(24, java.sql.Types.VARCHAR)
        ps.setInt(25, shop.stockCount)
    }

    private fun queryOne(sql: String, prep: PreparedStatement.() -> Unit): Shop? {
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.prep()
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    private fun queryMany(sql: String, prep: PreparedStatement.() -> Unit): List<Shop> {
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.prep()
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<Shop>()
                    while (rs.next()) out.add(mapRow(rs))
                    return out
                }
            }
        }
    }

    private fun mapRow(rs: ResultSet): Shop {
        val trustedStr = rs.getString("trusted") ?: ""
        val trusted = if (trustedStr.isBlank()) {
            emptySet()
        } else {
            // mapNotNull + runCatching so one corrupt trusted UUID can't throw and poison the
            // whole row load (which would drop every valid row after it in all()/findByOwner).
            trustedStr.split(",").filter { it.isNotBlank() }
                .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
        }
        val guildId = rs.getString("guild_id")?.takeIf { it.isNotBlank() }?.let { UUID.fromString(it) }
        val creatorId = rs.getString("creator_id")?.takeIf { it.isNotBlank() }?.let { UUID.fromString(it) }
        return Shop(
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
            trusted = trusted,
            hopperAllowIn = rs.getBoolean("hopper_allow_in"),
            hopperAllowOut = rs.getBoolean("hopper_allow_out"),
            frozen = rs.getBoolean("frozen"),
            adminShop = rs.getBoolean("admin_shop"),
            guildId = guildId,
            creatorId = creatorId,
            direction = runCatching {
                net.badgersmc.em.domain.shop.SignDirection.valueOf(
                    rs.getString("direction") ?: "SELL"
                )
            }.getOrDefault(net.badgersmc.em.domain.shop.SignDirection.SELL),
            searchEnabled = rs.getBoolean("search_enabled"),
            stockCount = rs.getInt("stock_count"),
        )
    }
}
