package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.maintenance.FreezeShiftResult
import net.badgersmc.em.domain.maintenance.MaintenanceFreezeRepository
import net.badgersmc.nexus.annotations.Repository
import java.time.Instant
import javax.sql.DataSource

/**
 * SQLite/MariaDB implementation of [MaintenanceFreezeRepository].
 *
 * The shift-on-unfreeze runs inside one transaction: both timer UPDATEs and
 * the freeze-row clear must succeed together — a partial shift would leave
 * some timers frozen and others not. autoCommit is restored in `finally`
 * (HikariCP pool-leak rule — never leave a connection with autoCommit off).
 */
@Repository
class MaintenanceFreezeRepositorySql(private val ds: DataSource) : MaintenanceFreezeRepository {

    override fun frozenSince(): Instant? {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT frozen, started_at FROM maintenance_freeze WHERE id = 1"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val frozen = rs.getInt("frozen") != 0
                    val startedAtMs = rs.getLong("started_at").takeIf { !rs.wasNull() }
                    return if (frozen && startedAtMs != null) Instant.ofEpochMilli(startedAtMs) else null
                }
            }
        }
    }

    override fun begin(now: Instant) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE maintenance_freeze SET frozen = 1, started_at = ? WHERE id = 1"
            ).use { ps ->
                ps.setLong(1, now.toEpochMilli())
                val updated = ps.executeUpdate()
                // REQ-303 — self-heal a deleted state row (audit L-2). The
                // UPDATE silently matching zero rows would leave the freeze
                // volatile across restarts; recreate the seed row instead.
                if (updated == 0) {
                    conn.prepareStatement(
                        "INSERT INTO maintenance_freeze (id, frozen, started_at) VALUES (1, 1, ?)"
                    ).use { insert ->
                        insert.setLong(1, now.toEpochMilli())
                        insert.executeUpdate()
                    }
                }
            }
        }
    }

    override fun unfreeze(shiftMs: Long): FreezeShiftResult {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                val stalls = shiftStallRentDeadlines(conn, shiftMs)
                val auctions = shiftOpenAuctionEnds(conn, shiftMs)
                conn.prepareStatement(
                    "UPDATE maintenance_freeze SET frozen = 0, started_at = NULL WHERE id = 1"
                ).use { ps -> ps.executeUpdate() }
                conn.commit()
                return FreezeShiftResult(stalls = stalls, auctions = auctions)
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun shiftStallRentDeadlines(conn: java.sql.Connection, shiftMs: Long): Int =
        conn.prepareStatement(
            "UPDATE stalls SET next_rent_at = next_rent_at + ? " +
                "WHERE next_rent_at IS NOT NULL AND state IN ('OWNED', 'GRACE')"
        ).use { ps ->
            ps.setLong(1, shiftMs)
            ps.executeUpdate()
        }

    private fun shiftOpenAuctionEnds(conn: java.sql.Connection, shiftMs: Long): Int =
        conn.prepareStatement(
            "UPDATE auctions SET end_at = end_at + ? WHERE state = 'OPEN' AND end_at < ?"
        ).use { ps ->
            ps.setLong(1, shiftMs)
            // Emergency auctions waiting for a first bid store the maximum
            // representable instant (Instant.ofEpochMilli(Long.MAX_VALUE)).
            // Shifting it would overflow a signed 64-bit long, and the
            // sentinel semantics ("no timer yet") mean it needs no shift.
            ps.setLong(2, Long.MAX_VALUE)
            ps.executeUpdate()
        }
}
