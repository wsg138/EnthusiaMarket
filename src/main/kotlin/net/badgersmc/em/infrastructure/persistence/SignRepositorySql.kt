package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.ShopSign
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.shop.SignRepository
import net.badgersmc.em.domain.stall.StallId
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

class SignRepositorySql(private val ds: DataSource) : SignRepository {

    override fun byStall(stallId: StallId): List<ShopSign> =
        queryMany("SELECT * FROM signs WHERE stall_id = ?") { setString(1, stallId.value) }

    override fun bySignLocation(loc: String): ShopSign? =
        queryOne("SELECT * FROM signs WHERE sign_location = ?") { setString(1, loc) }

    override fun create(sign: ShopSign): Long {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """INSERT INTO signs (stall_id, direction, item_key, price, sign_location, container_loc)
                   VALUES (?, ?, ?, ?, ?, ?)"""
            ).use { ps ->
                ps.setString(1, sign.stallId.value)
                ps.setString(2, sign.direction.name)
                ps.setString(3, sign.itemKey)
                ps.setLong(4, sign.price)
                ps.setString(5, sign.signLocation)
                ps.setString(6, sign.containerLocation)
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    if (rs.next()) return rs.getLong(1)
                }
            }
        }
        error("Failed to retrieve generated key for sign insert")
    }

    override fun save(sign: ShopSign) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """UPDATE signs SET stall_id = ?, direction = ?, item_key = ?,
                   price = ?, sign_location = ?, container_loc = ?
                   WHERE id = ?"""
            ).use { ps ->
                ps.setString(1, sign.stallId.value)
                ps.setString(2, sign.direction.name)
                ps.setString(3, sign.itemKey)
                ps.setLong(4, sign.price)
                ps.setString(5, sign.signLocation)
                ps.setString(6, sign.containerLocation)
                ps.setLong(7, sign.id)
                ps.executeUpdate()
            }
        }
    }

    override fun delete(id: Long) {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM signs WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
    }

    private fun queryOne(sql: String, prep: PreparedStatement.() -> Unit): ShopSign? {
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.prep()
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    private fun queryMany(sql: String, prep: PreparedStatement.() -> Unit): List<ShopSign> {
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.prep()
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ShopSign>()
                    while (rs.next()) out.add(mapRow(rs))
                    return out
                }
            }
        }
    }

    private fun mapRow(rs: ResultSet): ShopSign = ShopSign(
        id = rs.getLong("id"),
        stallId = StallId(rs.getString("stall_id")),
        direction = SignDirection.valueOf(rs.getString("direction")),
        itemKey = rs.getString("item_key"),
        price = rs.getLong("price"),
        signLocation = rs.getString("sign_location"),
        containerLocation = rs.getString("container_loc")
    )
}
