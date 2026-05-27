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

    override fun save(offer: SellOffer) {
        // PRIMARY KEY on stall_id makes this an upsert via INSERT OR REPLACE.
        ds.connection.use { conn ->
            conn.prepareStatement(
                """INSERT OR REPLACE INTO sell_offers
                   (stall_id, seller_uuid, price, created_at)
                   VALUES (?, ?, ?, ?)"""
            ).use { ps ->
                ps.setString(1, offer.stallId.value)
                ps.setString(2, offer.sellerUuid.toString())
                ps.setLong(3, offer.price)
                ps.setLong(4, offer.createdAt.toEpochMilli())
                ps.executeUpdate()
            }
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
