package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.nexus.persistence.DatabaseFactory
import net.badgersmc.nexus.persistence.DatabaseSpec
import net.badgersmc.nexus.persistence.MigrationRunner
import java.io.File
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies V027__unfreeze_owned_stalls.sql: shops on OWNED / UNOWNED stalls
 * are unfrozen while GRACE / EMERGENCY_AUCTIONING / AUCTIONING /
 * RE_AUCTIONING stalls keep their frozen shops. Regression guard for the
 * stall39 report (2026-08-01) where a stall recovered from GRACE via rent
 * payment but its shops stayed frozen forever.
 */
class UnfreezeOwnedStallsMigrationTest {

    private val dbFile = File.createTempFile("em-unfreeze-migration", ".db")
    private val ds: DataSource = DatabaseFactory.open(DatabaseSpec.Sqlite(dbFile))
    private val runner = MigrationRunner(ds, resourcePrefix = "migrations", classLoader = javaClass.classLoader)

    @AfterTest fun cleanup() { dbFile.delete() }

    @Test
    fun `V027 is discovered by the migration runner`() {
        assertTrue(runner.discover().any { it.version == 27 }, "V027 must be discovered on the classpath")
    }

    @Test
    fun `V027 unfreezes shops on OWNED stalls but keeps penalty stalls frozen`() {
        runner.runAll()
        seedFrozenShops()
        executeV027()

        val frozenByState = frozenCountsByState()
        assertEquals(0, frozenByState["OWNED"] ?: 0, "OWNED shops must be unfrozen")
        assertEquals(0, frozenByState["UNOWNED"] ?: 0, "UNOWNED shops must be unfrozen")
        assertEquals(1, frozenByState["GRACE"] ?: 0, "GRACE shops stay frozen")
        assertEquals(1, frozenByState["EMERGENCY_AUCTIONING"] ?: 0, "EMERGENCY_AUCTIONING shops stay frozen")
        assertEquals(1, frozenByState["AUCTIONING"] ?: 0, "AUCTIONING shops stay frozen")
        assertEquals(1, frozenByState["RE_AUCTIONING"] ?: 0, "RE_AUCTIONING shops stay frozen")
    }

    private fun seedFrozenShops() {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO stalls (id, region_id, world, state, owner_type, owner_id, rent_mode) " +
                        "VALUES ('stall39','stall39','world','OWNED','SOLO','00000000-0000-0000-0000-000000000001','FLAT')," +
                        "('stall4','stall4','world','GRACE','SOLO','00000000-0000-0000-0000-000000000002','FLAT')," +
                        "('stall5','stall5','world','EMERGENCY_AUCTIONING','NONE','','FLAT')," +
                        "('stall6','stall6','world','AUCTIONING','NONE','','FLAT')," +
                        "('stall7','stall7','world','RE_AUCTIONING','NONE','','FLAT')," +
                        "('stall8','stall8','world','UNOWNED','NONE','','FLAT')"
                )
                st.executeUpdate(
                    "INSERT INTO shop_items (stall_id, owner, sign_world, sign_x, sign_y, sign_z, " +
                        "container_world, container_x, container_y, container_z, sell_item, cost_item, frozen) " +
                        "VALUES ('stall39','00000000-0000-0000-0000-000000000001','world',1,1,1,'world',2,1,1,'a','b',1)," +
                        "('stall4','00000000-0000-0000-0000-000000000002','world',1,1,1,'world',2,1,1,'a','b',1)," +
                        "('stall5','','world',1,1,1,'world',2,1,1,'a','b',1)," +
                        "('stall6','','world',1,1,1,'world',2,1,1,'a','b',1)," +
                        "('stall7','','world',1,1,1,'world',2,1,1,'a','b',1)," +
                        "('stall8','','world',1,1,1,'world',2,1,1,'a','b',1)"
                )
            }
        }
    }

    private fun executeV027() {
        val sql = javaClass.classLoader.getResourceAsStream("migrations/V027__unfreeze_owned_stalls.sql")!!
            .bufferedReader().use { it.readText() }
        ds.connection.use { conn ->
            conn.createStatement().use { st -> st.executeUpdate(sql) }
        }
    }

    private fun frozenCountsByState(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT s.state, COUNT(i.id) FROM stalls s " +
                        "JOIN shop_items i ON i.stall_id = s.id AND i.frozen = 1 GROUP BY s.state"
                ).use { rs ->
                    while (rs.next()) counts[rs.getString(1)] = rs.getInt(2)
                }
            }
        }
        return counts
    }
}
