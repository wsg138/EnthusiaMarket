package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.offer.SellOffer
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

class SellOfferRepositorySqlTest {

    private lateinit var ds: HikariDataSource
    private lateinit var stallRepo: StallRepositorySql
    private lateinit var repo: SellOfferRepositorySql

    @BeforeTest fun setUp() {
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite::memory:"
            maximumPoolSize = 1
        })
        net.badgersmc.nexus.persistence.MigrationRunner(ds, "migrations", this::class.java.classLoader).runAll()
        stallRepo = StallRepositorySql(ds)
        repo = SellOfferRepositorySql(ds)

        // FK constraint requires the stall to exist first.
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

    @Test fun `save then findByStall round-trips`() {
        val seller = UUID.randomUUID()
        val created = Instant.ofEpochMilli(1_700_000_000_000L)
        repo.save(SellOffer(StallId("s1"), seller, 500L, created))

        val found = repo.findByStall(StallId("s1"))
        assertNotNull(found)
        assertEquals(seller, found.sellerUuid)
        assertEquals(500L, found.price)
        assertEquals(created, found.createdAt)
    }

    @Test fun `save is an upsert keyed on stall_id`() {
        val seller = UUID.randomUUID()
        repo.save(SellOffer(StallId("s1"), seller, 500L, Instant.now()))
        repo.save(SellOffer(StallId("s1"), seller, 750L, Instant.now()))

        val found = repo.findByStall(StallId("s1"))
        assertEquals(750L, found?.price)
        assertEquals(1, repo.all().size)
    }

    @Test fun `delete removes the offer`() {
        repo.save(SellOffer(StallId("s1"), UUID.randomUUID(), 500L, Instant.now()))
        repo.delete(StallId("s1"))
        assertNull(repo.findByStall(StallId("s1")))
    }

    @Test fun `findByStall on missing stall returns null`() {
        assertNull(repo.findByStall(StallId("s1")))
    }
}
