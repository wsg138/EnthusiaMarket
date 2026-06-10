package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.sign.PurchaseSign
import net.badgersmc.em.domain.sign.PurchaseSignRepository
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.nexus.annotations.Repository
import java.sql.ResultSet
import javax.sql.DataSource

@Repository
class PurchaseSignRepositorySql(private val ds: DataSource) : PurchaseSignRepository {

    override fun findAt(world: String, x: Int, y: Int, z: Int): PurchaseSign? {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT * FROM purchase_signs WHERE world = ? AND x = ? AND y = ? AND z = ?"
            ).use { ps ->
                ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun findByStall(stallId: StallId): List<PurchaseSign> {
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM purchase_signs WHERE stall_id = ?").use { ps ->
                ps.setString(1, stallId.value)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<PurchaseSign>()
                    while (rs.next()) out += mapRow(rs)
                    return out
                }
            }
        }
    }

    override fun all(): List<PurchaseSign> {
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM purchase_signs").use { ps ->
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<PurchaseSign>()
                    while (rs.next()) out += mapRow(rs)
                    return out
                }
            }
        }
    }

    // Portable UPDATE-then-INSERT on the (world,x,y,z) key (M-3): SQLite's
    // native upsert syntax breaks MariaDB, which the config also offers. If the
    // INSERT loses a first-writer race, the key throws — retry as an UPDATE.
    override fun save(sign: PurchaseSign) {
        ds.connection.use { conn ->
            if (updateRow(conn, sign) > 0) return
            try {
                insertRow(conn, sign)
            } catch (e: java.sql.SQLException) {
                if (updateRow(conn, sign) == 0) throw e
            }
        }
    }

    private fun updateRow(conn: java.sql.Connection, sign: PurchaseSign): Int =
        conn.prepareStatement(
            "UPDATE purchase_signs SET stall_id = ?, price = ? WHERE world = ? AND x = ? AND y = ? AND z = ?"
        ).use { ps ->
            ps.setString(1, sign.stallId.value)
            ps.setLong(2, sign.price)
            ps.setString(3, sign.world)
            ps.setInt(4, sign.x); ps.setInt(5, sign.y); ps.setInt(6, sign.z)
            ps.executeUpdate()
        }

    private fun insertRow(conn: java.sql.Connection, sign: PurchaseSign) {
        conn.prepareStatement(
            "INSERT INTO purchase_signs (world, x, y, z, stall_id, price) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, sign.world)
            ps.setInt(2, sign.x); ps.setInt(3, sign.y); ps.setInt(4, sign.z)
            ps.setString(5, sign.stallId.value)
            ps.setLong(6, sign.price)
            ps.executeUpdate()
        }
    }

    override fun deleteAt(world: String, x: Int, y: Int, z: Int) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM purchase_signs WHERE world = ? AND x = ? AND y = ? AND z = ?"
            ).use { ps ->
                ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z)
                ps.executeUpdate()
            }
        }
    }

    private fun mapRow(rs: ResultSet): PurchaseSign = PurchaseSign(
        stallId = StallId(rs.getString("stall_id")),
        world = rs.getString("world"),
        x = rs.getInt("x"),
        y = rs.getInt("y"),
        z = rs.getInt("z"),
        price = rs.getLong("price"),
    )
}
