package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.auction.AuctionState
import net.badgersmc.em.domain.auction.Bid
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.nexus.annotations.Repository
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Repository
class AuctionRepositorySql(private val ds: DataSource) : AuctionRepository {

    override fun findById(id: AuctionId): Auction? = queryOne("SELECT * FROM auctions WHERE id = ?") {
        setString(1, id.value)
    }

    override fun findOpenByStall(stallId: StallId): Auction? =
        queryOne("SELECT * FROM auctions WHERE stall_id = ? AND state = 'OPEN' AND end_at > ? ORDER BY start_at DESC LIMIT 1") {
            setString(1, stallId.value)
            setLong(2, Instant.now().toEpochMilli())
        }

    override fun allOpen(): List<Auction> =
        queryMany("SELECT * FROM auctions WHERE state = 'OPEN' AND end_at > ?") {
            setLong(1, Instant.now().toEpochMilli())
        }

    override fun create(auction: Auction) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """INSERT INTO auctions
                   (id, stall_id, state, start_at, end_at, starting_bid,
                    high_bid_amount, high_bidder, high_placed_at, anti_snipe_sec)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { ps ->
                bind(ps, auction)
                val rows = ps.executeUpdate()
                if (rows != 1) error("AuctionRepositorySql.create: expected 1 row, affected $rows")
            }
        }
    }

    override fun save(auction: Auction) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """UPDATE auctions
                   SET state = ?, start_at = ?, end_at = ?, starting_bid = ?,
                       high_bid_amount = ?, high_bidder = ?, high_placed_at = ?,
                       anti_snipe_sec = ?
                   WHERE id = ?"""
            ).use { ps ->
                ps.setString(1, auction.state.name)
                ps.setLong(2, auction.startAt.toEpochMilli())
                ps.setLong(3, auction.endAt.toEpochMilli())
                ps.setLong(4, auction.startingBid)
                if (auction.highBid != null) {
                    ps.setLong(5, auction.highBid.amount)
                    ps.setString(6, auction.highBid.bidder.toString())
                    ps.setLong(7, auction.highBid.placedAt.toEpochMilli())
                } else {
                    ps.setNull(5, java.sql.Types.INTEGER)
                    ps.setNull(6, java.sql.Types.VARCHAR)
                    ps.setNull(7, java.sql.Types.BIGINT)
                }
                ps.setLong(8, auction.antiSnipeWindow.toSeconds())
                ps.setString(9, auction.id.value)
                val rows = ps.executeUpdate()
                if (rows != 1) error("AuctionRepositorySql.save: expected 1 row, affected $rows")
            }
        }
    }

    override fun findExpired(): List<Auction> =
        queryMany("SELECT * FROM auctions WHERE state = 'OPEN' AND end_at <= ?") {
            setLong(1, Instant.now().toEpochMilli())
        }

    override fun delete(id: AuctionId) {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM auctions WHERE id = ?").use { ps ->
                ps.setString(1, id.value)
                val rows = ps.executeUpdate()
                if (rows != 1) error("AuctionRepositorySql.delete: expected 1 row, affected $rows")
            }
        }
    }

    private fun bind(ps: PreparedStatement, auction: Auction) {
        ps.setString(1, auction.id.value)
        ps.setString(2, auction.stallId.value)
        ps.setString(3, auction.state.name)
        ps.setLong(4, auction.startAt.toEpochMilli())
        ps.setLong(5, auction.endAt.toEpochMilli())
        ps.setLong(6, auction.startingBid)
        if (auction.highBid != null) {
            ps.setLong(7, auction.highBid.amount)
            ps.setString(8, auction.highBid.bidder.toString())
            ps.setLong(9, auction.highBid.placedAt.toEpochMilli())
        } else {
            ps.setNull(7, java.sql.Types.INTEGER)
            ps.setNull(8, java.sql.Types.VARCHAR)
            ps.setNull(9, java.sql.Types.BIGINT)
        }
        ps.setLong(10, auction.antiSnipeWindow.toSeconds())
    }

    private fun queryOne(sql: String, prep: PreparedStatement.() -> Unit): Auction? {
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.prep()
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    private fun queryMany(sql: String, prep: PreparedStatement.() -> Unit): List<Auction> {
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.prep()
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<Auction>()
                    while (rs.next()) out.add(mapRow(rs))
                    return out
                }
            }
        }
    }

    private fun mapRow(rs: ResultSet): Auction {
        val highBidAmount = rs.getLong("high_bid_amount").takeIf { !rs.wasNull() }
        val highBidderStr = rs.getString("high_bidder")
        val highPlacedAtMs = rs.getLong("high_placed_at").takeIf { !rs.wasNull() }
        val highBid = if (highBidAmount != null && highBidderStr != null && highPlacedAtMs != null) {
            Bid(
                bidder = UUID.fromString(highBidderStr),
                amount = highBidAmount,
                placedAt = Instant.ofEpochMilli(highPlacedAtMs)
            )
        } else {
            null
        }
        return Auction(
            id = AuctionId(rs.getString("id")),
            stallId = StallId(rs.getString("stall_id")),
            state = AuctionState.valueOf(rs.getString("state")),
            startAt = Instant.ofEpochMilli(rs.getLong("start_at")),
            endAt = Instant.ofEpochMilli(rs.getLong("end_at")),
            startingBid = rs.getLong("starting_bid"),
            highBid = highBid,
            antiSnipeWindow = Duration.ofSeconds(rs.getLong("anti_snipe_sec"))
        )
    }
}