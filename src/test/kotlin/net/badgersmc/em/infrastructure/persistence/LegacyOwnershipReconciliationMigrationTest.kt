package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.nexus.persistence.DatabaseFactory
import net.badgersmc.nexus.persistence.DatabaseSpec
import net.badgersmc.nexus.persistence.MigrationRunner
import java.io.File
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression coverage for V029 ownership-integrity reconciliation.
 *
 * The migration is intentionally conservative: it repairs only database state
 * that can be proven stale from the authoritative stall row and never guesses
 * at guild-player relationships or live moderation snapshots.
 */
class LegacyOwnershipReconciliationMigrationTest {

    private val dbFile = File.createTempFile("em-ownership-reconciliation", ".db")
    private val ds: DataSource = DatabaseFactory.open(DatabaseSpec.Sqlite(dbFile))
    private val runner = MigrationRunner(ds, resourcePrefix = "migrations", classLoader = javaClass.classLoader)

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    @Test
    fun `V029 reconciles only provably stale ownership data and is idempotent`() {
        // Build the current schema, then mark only the data-only V029 migration
        // pending so the populated fixture is upgraded through MigrationRunner.
        runner.runAll()
        markMigrationPending(29)
        seedLegacyRows()

        val applied = runner.runAll()
        assertEquals(listOf(29), applied.map { it.version })
        assertReconciledState()

        val firstPassShopIds = shopIds()
        val firstPassStalls = snapshotStalls()

        // Re-running the SQL must be safe. Production operators may validate a
        // copied database more than once before rollout.
        executeV029()

        assertEquals(firstPassShopIds, shopIds())
        assertEquals(firstPassStalls, snapshotStalls())
    }

    @Test
    fun `V029 is discovered by the migration runner`() {
        assertTrue(runner.discover().any { it.version == 29 }, "V029 must be discovered on the classpath")
    }

    private fun markMigrationPending(version: Int) {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM schema_migration WHERE version = ?").use { ps ->
                ps.setInt(1, version)
                ps.executeUpdate()
            }
        }
    }

    private fun seedLegacyRows() {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO stalls
                        (id, region_id, world, state, owner_type, owner_id, owner_since,
                         winning_bid, rent_mode, members, next_rent_at)
                    VALUES
                        ('solo','solo','world','OWNED','SOLO','00000000-0000-0000-0000-000000000001',100,1000,'FLAT','00000000-0000-0000-0000-000000000002',200),
                        ('grace','grace','world','GRACE','SOLO','00000000-0000-0000-0000-000000000011',100,1000,'FLAT','',200),
                        ('guild','guild','world','OWNED','GUILD','guild-alpha',100,1000,'FLAT','00000000-0000-0000-0000-000000000099',200),
                        ('vacant','vacant','world','UNOWNED','SOLO','00000000-0000-0000-0000-000000000004',100,999,'FLAT','00000000-0000-0000-0000-000000000094',200),
                        ('emergency','emergency','world','EMERGENCY_AUCTIONING','SOLO','00000000-0000-0000-0000-000000000005',100,888,'FLAT','00000000-0000-0000-0000-000000000095',200),
                        ('auction','auction','world','AUCTIONING','NONE','',NULL,0,'FLAT','00000000-0000-0000-0000-000000000096',NULL),
                        ('reauction','reauction','world','RE_AUCTIONING','NONE','',NULL,0,'FLAT','00000000-0000-0000-0000-000000000097',NULL),
                        ('locked','locked','world','OWNED','SOLO','00000000-0000-0000-0000-000000000006',100,777,'FLAT','00000000-0000-0000-0000-000000000098',200),
                        ('hold','hold','world','MODERATION_HOLD','NONE','',NULL,0,'FLAT','00000000-0000-0000-0000-000000000093',NULL)
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO market_moderation_locks (stall_id, operation_id, acquired_at)
                    VALUES ('locked','00000000-0000-0000-0000-000000000901',123)
                    """.trimIndent()
                )

                insertShop(st, 1, "solo", "00000000-0000-0000-0000-000000000001", admin = 0)
                insertShop(st, 2, "solo", "00000000-0000-0000-0000-000000000002", admin = 0)
                insertShop(st, 3, "solo", "00000000-0000-0000-0000-000000000002", admin = 1)
                insertShop(st, 4, "guild", "00000000-0000-0000-0000-000000000003", admin = 0)
                insertShop(st, 5, "vacant", "00000000-0000-0000-0000-000000000004", admin = 0)
                insertShop(st, 6, "emergency", "00000000-0000-0000-0000-000000000005", admin = 0)
                insertShop(st, 7, "emergency", "00000000-0000-0000-0000-000000000005", admin = 1)
                insertShop(st, 8, "auction", "00000000-0000-0000-0000-000000000008", admin = 0)
                insertShop(st, 9, "reauction", "00000000-0000-0000-0000-000000000009", admin = 0)
                insertShop(st, 10, "locked", "00000000-0000-0000-0000-000000000010", admin = 0)
                insertShop(st, 11, "hold", "00000000-0000-0000-0000-000000000011", admin = 0)
                insertShop(st, 12, "grace", "00000000-0000-0000-0000-000000000012", admin = 0)
                insertShop(st, 13, "solo", "00000000-0000-0000-0000-000000000013", admin = 0)
            }
        }
    }

    private fun insertShop(
        st: java.sql.Statement,
        id: Long,
        stallId: String,
        owner: String,
        admin: Int,
    ) {
        st.executeUpdate(
            """
            INSERT INTO shop_items
                (id, stall_id, owner, sign_world, sign_x, sign_y, sign_z,
                 container_world, container_x, container_y, container_z,
                 sell_item, cost_item, admin_shop)
            VALUES
                ($id, '$stallId', '$owner', 'world', $id, 64, 0,
                 'world', $id, 63, 0, 'sell', 'cost', $admin)
            """.trimIndent()
        )
    }

    private fun executeV029() {
        val stream = javaClass.classLoader.getResourceAsStream(
            "migrations/V029__ownership_integrity_reconciliation.sql"
        )
        val sql = assertNotNull(stream, "V029 migration resource must exist")
            .bufferedReader()
            .use { it.readText() }

        val statements = sql.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                statements.forEach(st::executeUpdate)
            }
        }
    }

    private fun assertReconciledState() {
        // Keep the current-owner SOLO shop, current delegated-member shop,
        // admin shop, guild shop, emergency admin shop, moderation-locked stale
        // shop, and moderation-hold shop. The unrelated SOLO shop (13) is stale.
        assertEquals(setOf(1L, 2L, 3L, 4L, 7L, 10L, 11L), shopIds())

        val rows = snapshotStalls()

        val vacant = rows.getValue("vacant")
        assertEquals("NONE", vacant.ownerType)
        assertEquals("", vacant.ownerId)
        assertEquals(null, vacant.ownerSince)
        assertEquals(0L, vacant.winningBid)
        assertEquals("", vacant.members)
        assertEquals(null, vacant.nextRentAt)

        val emergency = rows.getValue("emergency")
        assertEquals("SOLO", emergency.ownerType, "former owner remains only as emergency-auction provenance")
        assertEquals("00000000-0000-0000-0000-000000000005", emergency.ownerId)
        assertEquals("", emergency.members)

        assertEquals("", rows.getValue("auction").members)
        assertEquals("", rows.getValue("reauction").members)

        // Guild and moderation-owned data is intentionally untouched.
        assertEquals("guild-alpha", rows.getValue("guild").ownerId)
        assertEquals("00000000-0000-0000-0000-000000000099", rows.getValue("guild").members)
        assertEquals("00000000-0000-0000-0000-000000000098", rows.getValue("locked").members)
        assertEquals("00000000-0000-0000-0000-000000000093", rows.getValue("hold").members)
    }

    private fun shopIds(): Set<Long> {
        val ids = linkedSetOf<Long>()
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT id FROM shop_items ORDER BY id").use { rs ->
                    while (rs.next()) ids += rs.getLong(1)
                }
            }
        }
        return ids
    }

    private data class StallSnapshot(
        val ownerType: String,
        val ownerId: String,
        val ownerSince: Long?,
        val winningBid: Long,
        val members: String,
        val nextRentAt: Long?,
    )

    private fun snapshotStalls(): Map<String, StallSnapshot> {
        val rows = linkedMapOf<String, StallSnapshot>()
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT id, owner_type, owner_id, owner_since, winning_bid, members, next_rent_at
                    FROM stalls
                    ORDER BY id
                    """.trimIndent()
                ).use { rs ->
                    while (rs.next()) {
                        rows[rs.getString("id")] = StallSnapshot(
                            ownerType = rs.getString("owner_type"),
                            ownerId = rs.getString("owner_id"),
                            ownerSince = rs.nullableLong("owner_since"),
                            winningBid = rs.getLong("winning_bid"),
                            members = rs.getString("members"),
                            nextRentAt = rs.nullableLong("next_rent_at"),
                        )
                    }
                }
            }
        }
        return rows
    }

    private fun java.sql.ResultSet.nullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }
}
