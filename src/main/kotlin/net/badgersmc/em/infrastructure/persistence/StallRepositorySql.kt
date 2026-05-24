package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.stall.*
import net.badgersmc.nexus.annotations.Repository
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource

@Repository
class StallRepositorySql(private val ds: DataSource) : StallRepository {

    override fun findById(id: StallId): Stall? = queryOne("SELECT * FROM stalls WHERE id = ?") {
        setString(1, id.value)
    }

    override fun findByRegion(world: String, regionId: String): Stall? =
        queryOne("SELECT * FROM stalls WHERE world = ? AND region_id = ?") {
            setString(1, world); setString(2, regionId)
        }

    override fun all(): List<Stall> = queryMany("SELECT * FROM stalls") {}

    override fun byState(state: StallState): List<Stall> =
        queryMany("SELECT * FROM stalls WHERE state = ?") { setString(1, state.name) }

    override fun create(stall: Stall) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """INSERT INTO stalls
                   (id, region_id, world, state, owner_type, owner_id, owner_since,
                    winning_bid, rent_mode, rent_pct, rent_flat)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { ps ->
                bind(ps, stall)
                ps.executeUpdate()
            }
        }
    }

    override fun save(stall: Stall) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """UPDATE stalls SET
                     region_id = ?, world = ?, state = ?, owner_type = ?, owner_id = ?,
                     owner_since = ?, winning_bid = ?, rent_mode = ?, rent_pct = ?, rent_flat = ?
                   WHERE id = ?"""
            ).use { ps ->
                ps.setString(1, stall.regionId)
                ps.setString(2, stall.world)
                ps.setString(3, stall.state.name)
                ps.setString(4, stall.owner.type.name)
                ps.setString(5, stall.owner.id)
                if (stall.ownerSince != null) ps.setLong(6, stall.ownerSince.toEpochMilli())
                else ps.setNull(6, java.sql.Types.INTEGER)
                ps.setLong(7, stall.winningBid)
                ps.setString(8, stall.rentTerms.mode.name)
                ps.setDouble(9, stall.rentTerms.pct)
                ps.setLong(10, stall.rentTerms.flatAmount)
                ps.setString(11, stall.id.value)
                ps.executeUpdate()
            }
        }
    }

    private fun bind(ps: PreparedStatement, stall: Stall) {
        ps.setString(1, stall.id.value)
        ps.setString(2, stall.regionId)
        ps.setString(3, stall.world)
        ps.setString(4, stall.state.name)
        ps.setString(5, stall.owner.type.name)
        ps.setString(6, stall.owner.id)
        if (stall.ownerSince != null) ps.setLong(7, stall.ownerSince.toEpochMilli())
        else ps.setNull(7, java.sql.Types.INTEGER)
        ps.setLong(8, stall.winningBid)
        ps.setString(9, stall.rentTerms.mode.name)
        ps.setDouble(10, stall.rentTerms.pct)
        ps.setLong(11, stall.rentTerms.flatAmount)
    }

    private fun queryOne(sql: String, prep: PreparedStatement.() -> Unit): Stall? {
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.prep()
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    private fun queryMany(sql: String, prep: PreparedStatement.() -> Unit): List<Stall> {
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.prep()
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<Stall>()
                    while (rs.next()) out.add(mapRow(rs))
                    return out
                }
            }
        }
    }

    private fun mapRow(rs: ResultSet): Stall {
        val ownerType = OwnerType.valueOf(rs.getString("owner_type"))
        val ownerId = rs.getString("owner_id")
        val owner = if (ownerType == OwnerType.NONE) OwnerRef.unowned() else OwnerRef(ownerType, ownerId)
        val ownerSinceMs = rs.getLong("owner_since").takeIf { !rs.wasNull() }
        val rentMode = RentTerms.Mode.valueOf(rs.getString("rent_mode"))
        val rentTerms = RentTerms(rentMode, rs.getDouble("rent_pct"), rs.getLong("rent_flat"))
        return Stall(
            id = StallId(rs.getString("id")),
            regionId = rs.getString("region_id"),
            world = rs.getString("world"),
            state = StallState.valueOf(rs.getString("state")),
            owner = owner,
            ownerSince = ownerSinceMs?.let { Instant.ofEpochMilli(it) },
            winningBid = rs.getLong("winning_bid"),
            rentTerms = rentTerms
        )
    }
}
