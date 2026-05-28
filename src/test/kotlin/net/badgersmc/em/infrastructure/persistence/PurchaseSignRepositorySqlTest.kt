package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.sign.PurchaseSign
import net.badgersmc.em.domain.sign.PurchaseSignKind
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallState
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PurchaseSignRepositorySqlTest {

    private lateinit var ds: HikariDataSource
    private lateinit var stallRepo: StallRepositorySql
    private lateinit var repo: PurchaseSignRepositorySql

    @BeforeTest fun setUp() {
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite::memory:"
            maximumPoolSize = 1
        })
        net.badgersmc.nexus.persistence.MigrationRunner(ds, "migrations", this::class.java.classLoader).runAll()
        stallRepo = StallRepositorySql(ds)
        repo = PurchaseSignRepositorySql(ds)

        // FK depends on the stall existing first.
        stallRepo.create(
            Stall(
                id = StallId("s1"),
                regionId = "s1",
                world = "world",
                state = StallState.OWNED,
                owner = OwnerRef.solo(UUID.randomUUID()),
                ownerSince = Instant.now(),
                winningBid = 100L,
                rentTerms = RentTerms.formula(1.0),
            )
        )
    }

    @AfterTest fun tearDown() { ds.close() }

    @Test fun `save then findAt round-trips`() {
        val sign = PurchaseSign(StallId("s1"), "world", 10, 64, 20, PurchaseSignKind.BUY)
        repo.save(sign)

        val found = repo.findAt("world", 10, 64, 20)
        assertNotNull(found)
        assertEquals(PurchaseSignKind.BUY, found.kind)
        assertEquals(StallId("s1"), found.stallId)
    }

    @Test fun `findByStall returns every sign bound to a stall`() {
        repo.save(PurchaseSign(StallId("s1"), "world", 1, 64, 1, PurchaseSignKind.BUY))
        repo.save(PurchaseSign(StallId("s1"), "world", 2, 64, 2, PurchaseSignKind.RENT))
        assertEquals(2, repo.findByStall(StallId("s1")).size)
    }

    @Test fun `deleteAt removes the binding`() {
        repo.save(PurchaseSign(StallId("s1"), "world", 5, 64, 5, PurchaseSignKind.BUY))
        repo.deleteAt("world", 5, 64, 5)
        assertNull(repo.findAt("world", 5, 64, 5))
    }

    @Test fun `save is an upsert keyed on coordinates`() {
        repo.save(PurchaseSign(StallId("s1"), "world", 7, 64, 7, PurchaseSignKind.BUY))
        repo.save(PurchaseSign(StallId("s1"), "world", 7, 64, 7, PurchaseSignKind.INFO))
        val found = repo.findAt("world", 7, 64, 7)
        assertEquals(PurchaseSignKind.INFO, found?.kind)
        assertEquals(1, repo.findByStall(StallId("s1")).size)
    }
}
