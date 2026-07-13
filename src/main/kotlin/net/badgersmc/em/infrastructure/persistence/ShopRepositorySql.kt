package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID
import javax.sql.DataSource

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

    override fun all(): List<Shop> = queryMany("SELECT * FROM shop_items") {}

    override fun countAll(): Int = queryCount("SELECT COUNT(*) FROM shop_items") {}
    override fun countByOwner(owner: UUID): Int =
        queryCount("SELECT COUNT(*) FROM shop_items WHERE owner = ?") { setString(1, owner.toString()) }

    override fun delete(id: Long) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM shop_transactions WHERE shop_id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM shop_items WHERE id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeUpdate()
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

    override fun deleteByContainer(world: String, x: Int, y: Int, z: Int) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """DELETE FROM shop_transactions WHERE shop_id IN
                       (SELECT id FROM shop_items
                        WHERE container_world = ? AND container_x = ? AND container_y = ? AND container_z = ?)"""
                ).use { ps ->
                    ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "DELETE FROM shop_items WHERE container_world = ? AND container_x = ? AND container_y = ? AND container_z = ?"
                ).use { ps ->
                    ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z)
                    ps.executeUpdate()
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

    override fun deleteByOwner(owner: UUID): Int {
        return ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "DELETE FROM shop_transactions WHERE shop_id IN (SELECT id FROM shop_items WHERE owner = ?)"
                ).use { ps ->
                    ps.setString(1, owner.toString())
                    ps.executeUpdate()
                }
                val count = conn.prepareStatement("DELETE FROM shop_items WHERE owner = ?").use { ps ->
                    ps.setString(1, owner.toString())
                    ps.executeUpdate()
                }
                conn.commit()
                count
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
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

    override fun updateStockBatch(batch: Map<Long, Int>) {
        if (batch.isEmpty()) return
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE shop_items SET stock_count = ? WHERE id = ?").use { ps ->
                for ((id, stock) in batch) {
                    ps.setInt(1, stock)
                    ps.setLong(2, id)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    override fun freezeByStall(stallId: String, frozen: Boolean) {
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE shop_items SET frozen = ? WHERE stall_id = ?").use { ps ->
                ps.setBoolean(1, frozen)
                ps.setString(2, stallId)
                ps.executeUpdate()
            }
        }
    }

    override fun findBySellMaterial(material: String): List<Shop> =
        queryMany("SELECT * FROM shop_items WHERE sell_material = ? AND search_enabled = 1") {
            setString(1, material)
        }

    override fun findBySellMaterialPrefix(prefix: String): List<Shop> =
        queryMany("SELECT * FROM shop_items WHERE sell_material LIKE ? ESCAPE '\\' AND search_enabled = 1") {
            // Escape LIKE wildcards in the user-supplied prefix, then append % for prefix match
            setString(1, "${prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%")
        }

    @Suppress("NestedBlockDepth")
    override fun backfillSellMaterials(): Int {
        var updated = 0
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT id, sell_item FROM shop_items WHERE sell_material IS NULL OR sell_material = ''"
            ).use { selectPs ->
                selectPs.executeQuery().use { rs ->
                    while (rs.next()) {
                        val material = extractMaterial(rs.getString("sell_item"))
                        if (material != null) {
                            updateSellMaterial(conn, rs.getLong("id"), material)
                            updated++
                        }
                    }
                }
            }
        }
        return updated
    }

    private fun extractMaterial(base64: String): String? {
        return try {
            val bytes = java.util.Base64.getDecoder().decode(base64)
            // Try modern Paper data-component format first (serializeAsBytes output)
            runCatching { org.bukkit.inventory.ItemStack.deserializeBytes(bytes) }
                .getOrNull()?.type?.name?.let { return it }
            // Fall back to legacy Java-serialized format (pre-Paper 1.20.5)
            java.io.ByteArrayInputStream(bytes).use { stream ->
                runCatching {
                    org.bukkit.util.io.BukkitObjectInputStream(stream).use { ois ->
                        (ois.readObject() as org.bukkit.inventory.ItemStack).type.name
                    }
                }.getOrNull()
            }?.let { return it }
            // Last resort: gzip-compressed NBT (oldest format, still in live DBs)
            extractMaterialFromNBT(bytes)
        } catch (_: Exception) { null }
    }

    /** Extracts the item id ("minecraft:diamond" → "DIAMOND") from gzip-compressed NBT. */
    @Suppress("CyclomaticComplexMethod")
    private fun extractMaterialFromNBT(raw: ByteArray): String? {
        val data = try {
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(raw)).readBytes()
        } catch (_: Exception) { return null }
        if (data.size < 3 || data[0] != 0x0A.toByte()) return null
        var pos = 3 // skip TAG_Compound header (type + 2-byte empty name)
        while (pos < data.size) {
            val tagId = data[pos++].toInt() and 0xFF
            if (tagId == 0x00) break // TAG_End
            val nameLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            val name = if (nameLen > 0) String(data, pos, nameLen, Charsets.UTF_8) else ""
            pos += nameLen
            when (tagId) {
                0x08 -> { // TAG_String
                    val valLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    pos += 2
                    val value = String(data, pos, valLen, Charsets.UTF_8)
                    pos += valLen
                    if (name == "id") {
                        return if (value.startsWith("minecraft:")) value.removePrefix("minecraft:").uppercase()
                        else value.uppercase()
                    }
                }
                0x01 -> pos += 1                     // TAG_Byte
                0x02 -> pos += 2                     // TAG_Short
                0x03, 0x05 -> pos += 4               // TAG_Int, TAG_Float
                0x04, 0x06 -> pos += 8               // TAG_Long, TAG_Double
                0x07 -> pos += 4 + readIntBigEndian(data, pos)
                0x0A -> { /* TAG_Compound — skip compound body by recursing to TAG_End */ pos = skipCompound(data, pos) }
                0x0B -> pos += 4 + (4 * readIntBigEndian(data, pos))
                0x0C -> pos += 4 + (8 * readIntBigEndian(data, pos))
                0x09 -> { // TAG_List
                    val childType = data[pos++].toInt() and 0xFF
                    val count = readIntBigEndian(data, pos)
                    pos += 4
                    // Skip list entries by size
                    repeat(count) { pos = skipSimpleNbt(data, pos, childType) }
                }
            }
        }
        return null
    }

    private fun readIntBigEndian(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun skipSimpleNbt(data: ByteArray, pos: Int, tagId: Int): Int {
        return when (tagId) {
            0x01 -> pos + 1
            0x02 -> pos + 2
            0x03, 0x05 -> pos + 4
            0x04, 0x06 -> pos + 8
            0x08 -> {
                val len = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos + 2 + len
            }
            0x07 -> pos + 4 + readIntBigEndian(data, pos)
            0x0B -> pos + 4 + (4 * readIntBigEndian(data, pos))
            0x0C -> pos + 4 + (8 * readIntBigEndian(data, pos))
            0x0A -> skipCompound(data, pos)
            0x09 -> { // TAG_List
                val childType = data[pos].toInt() and 0xFF
                val count = readIntBigEndian(data, pos + 1)
                var skipPos = pos + 5
                @Suppress("UNUSED_VARIABLE")
                for (i in 0 until count) {
                    skipPos = skipSimpleNbt(data, skipPos, childType)
                }
                skipPos
            }
            else -> pos
        }
    }

    private fun skipCompound(data: ByteArray, start: Int): Int {
        var pos = start
        while (pos < data.size) {
            val tagId = data[pos++].toInt() and 0xFF
            if (tagId == 0x00) return pos
            val nameLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2 + nameLen
            pos = skipSimpleNbt(data, pos, tagId)
        }
        return pos
    }

    private fun updateSellMaterial(conn: java.sql.Connection, id: Long, material: String) {
        conn.prepareStatement("UPDATE shop_items SET sell_material = ? WHERE id = ?").use { ps ->
            ps.setString(1, material)
            ps.setLong(2, id)
            ps.executeUpdate()
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
                 direction, search_enabled, sell_material, stock_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                ps.setLong(24, shop.id)
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
              direction = ?, search_enabled = ?, sell_material = ?, stock_count = ?
            WHERE id = ?
        """.trimIndent()
    }

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
        ps.setString(20, shop.direction.name)
        ps.setBoolean(21, shop.searchEnabled)
        ps.setString(22, extractMaterial(shop.sellItem))
        ps.setInt(23, shop.stockCount)
    }

    private fun queryCount(sql: String, prep: PreparedStatement.() -> Unit): Int {
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.prep()
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
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
            trustedStr.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                .toSet()
        }
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