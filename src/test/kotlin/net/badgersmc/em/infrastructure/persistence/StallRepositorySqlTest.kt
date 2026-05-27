package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallState
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StallRepositorySqlTest {
    private lateinit var ds: HikariDataSource
    private lateinit var repo: StallRepositorySql

    @BeforeTest fun setUp() {
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite::memory:"
            maximumPoolSize = 1
        })
        net.badgersmc.nexus.persistence.MigrationRunner(ds, "migrations", this::class.java.classLoader).runAll()
        repo = StallRepositorySql(ds)
    }

    @AfterTest fun tearDown() { ds.close() }

    private fun newUnowned(id: String, region: String = id) = Stall(
        id = StallId(id),
        regionId = region,
        world = "world",
        state = StallState.UNOWNED,
        owner = OwnerRef.unowned(),
        ownerSince = null,
        winningBid = 0L,
        rentTerms = RentTerms.formula(1.0)
    )

    @Test fun `create then findById round-trips`() {
        val s = newUnowned("stall_01")
        repo.create(s)
        val found = repo.findById(StallId("stall_01"))
        assertNotNull(found)
        assertEquals(s.regionId, found.regionId)
        assertEquals(StallState.UNOWNED, found.state)
    }

    @Test fun `unknown id returns null`() {
        assertNull(repo.findById(StallId("nope")))
    }

    @Test fun `byState filters correctly`() {
        repo.create(newUnowned("s1"))
        repo.create(newUnowned("s2").copy(state = StallState.OWNED, winningBid = 500L))
        val unowned = repo.byState(StallState.UNOWNED)
        assertEquals(1, unowned.size)
        assertEquals("s1", unowned[0].id.value)
    }

    @Test fun `save updates state and bid`() {
        val s = newUnowned("s1")
        repo.create(s)
        val updated = s.copy(state = StallState.OWNED, winningBid = 1234L)
        repo.save(updated)
        val found = repo.findById(StallId("s1"))!!
        assertEquals(StallState.OWNED, found.state)
        assertEquals(1234L, found.winningBid)
    }

    // --- REQ-200 / REQ-201 — members + maxMembers persistence ---

    @Test fun `members and maxMembers round-trip through create + findById`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val s = newUnowned("s1").copy(members = setOf(a, b), maxMembers = 5)
        repo.create(s)

        val found = repo.findById(StallId("s1"))!!
        assertEquals(setOf(a, b), found.members)
        assertEquals(5, found.maxMembers)
    }

    @Test fun `default members is empty and default maxMembers is -1 after migration`() {
        // Existing rows migrated forward should default to "" / -1.
        val s = newUnowned("s1")
        repo.create(s)
        val found = repo.findById(StallId("s1"))!!
        assertEquals(emptySet(), found.members)
        assertEquals(-1, found.maxMembers)
    }

    @Test fun `save persists member roster mutations`() {
        val s = newUnowned("s1")
        repo.create(s)
        val player = UUID.randomUUID()
        repo.save(s.addMember(player))

        val reloaded = repo.findById(StallId("s1"))!!
        assertEquals(setOf(player), reloaded.members)
    }
}
