package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.offer.SellOffer
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.nexus.annotations.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Repository
class SellOfferRepositorySql(private val ds: DataSource) : SellOfferRepository {

    override fun findByStall(stallId: StallId): SellOffer? {
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM sell_offers WHERE stall_id = ?").use { ps ->
                ps.setString(1, stallId.value)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun all(): List<SellOffer> {
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM sell_offers").use { ps ->
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<SellOffer>()
                    while (rs.next()) out += mapRow(rs)
                    return out
                }
            }
        }
    }

    // Portable UPDATE-then-INSERT on the stall_id primary key (M-3): SQLite's
    // native upsert syntax breaks MariaDB, which the config also offers. If the
    // INSERT loses a first-writer race, the key throws — retry as an UPDATE.
    override fun save(offer: SellOffer) {
        ds.connection.use { conn ->
            if (updateRow(conn, offer) > 0) return
            try {
                insertRow(conn, offer)
            } catch (e: java.sql.SQLException) {
                if (updateRow(conn, offer) == 0) throw e
            }
        }
    }

    private fun updateRow(conn: java.sql.Connection, offer: SellOffer): Int =
        conn.prepareStatement(
            "UPDATE sell_offers SET seller_uuid = ?, price = ?, created_at = ? WHERE stall_id = ?"
        ).use { ps ->
            ps.setString(1, offer.sellerUuid.toString())
            ps.setLong(2, offer.price)
            ps.setLong(3, offer.createdAt.toEpochMilli())
            ps.setString(4, offer.stallId.value)
            ps.executeUpdate()
        }

    private fun insertRow(conn: java.sql.Connection, offer: SellOffer) {
        conn.prepareStatement(
            "INSERT INTO sell_offers (stall_id, seller_uuid, price, created_at) VALUES (?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, offer.stallId.value)
            ps.setString(2, offer.sellerUuid.toString())
            ps.setLong(3, offer.price)
            ps.setLong(4, offer.createdAt.toEpochMilli())
            ps.executeUpdate()
        }
    }

    override fun delete(stallId: StallId) {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM sell_offers WHERE stall_id = ?").use { ps ->
                ps.setString(1, stallId.value)
                ps.executeUpdate()
            }
        }
    }

    private fun mapRow(rs: ResultSet): SellOffer = SellOffer(
        stallId = StallId(rs.getString("stall_id")),
        sellerUuid = UUID.fromString(rs.getString("seller_uuid")),
        price = rs.getLong("price"),
        createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
    )
}
