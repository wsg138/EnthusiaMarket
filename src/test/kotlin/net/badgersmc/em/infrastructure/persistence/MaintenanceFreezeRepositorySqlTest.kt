package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionState
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.domain.stall.RentTerms
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MaintenanceFreezeRepositorySqlTest {

    private lateinit var ds: HikariDataSource
    private lateinit var repo: MaintenanceFreezeRepositorySql
    private lateinit var stalls: StallRepositorySql
    private lateinit var auctions: AuctionRepositorySql

    @BeforeTest
    fun setUp() {
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite::memory:"
            maximumPoolSize = 1
        })
        net.badgersmc.nexus.persistence.MigrationRunner(ds, "migrations", this::class.java.classLoader).runAll()
        repo = MaintenanceFreezeRepositorySql(ds)
        stalls = StallRepositorySql(ds)
        auctions = AuctionRepositorySql(ds)
    }

    @AfterTest
    fun tearDown() { ds.close() }

    private fun stall(
        id: String,
        state: StallState = StallState.OWNED,
        nextRentAt: Instant? = null,
    ) = Stall(
        id = StallId(id), regionId = id, world = "world",
        state = state, owner = OwnerRef.solo(java.util.UUID.randomUUID()),
        ownerSince = Instant.parse("2026-01-01T00:00:00Z"), winningBid = 100L,
        rentTerms = RentTerms.formula(1.0), nextRentAt = nextRentAt,
    )

    private fun auction(
        id: String,
        state: AuctionState = AuctionState.OPEN,
        endAt: Instant,
    ) = Auction(
        id = AuctionId(id), stallId = StallId("stall_01"), state = state,
        startAt = Instant.parse("2026-01-01T00:00:00Z"), endAt = endAt,
        startingBid = 100L, highBid = null,
        antiSnipeWindow = Duration.ofMinutes(1),
        antiSnipeExtension = Duration.ofMinutes(1),
        auctionDuration = Duration.ofDays(1),
    )

    // --- freeze flag round-trip ---

    @Test fun `frozenSince is null before any freeze`() {
        assertNull(repo.frozenSince())
    }

    @Test fun `begin then frozenSince returns the start instant`() {
        val start = Instant.parse("2026-06-01T10:00:00Z")
        repo.begin(start)
        assertEquals(start, repo.frozenSince())
    }

    @Test fun `begin overwrites a previous start instant`() {
        repo.begin(Instant.parse("2026-06-01T10:00:00Z"))
        val restart = Instant.parse("2026-06-02T08:00:00Z")
        repo.begin(restart)
        assertEquals(restart, repo.frozenSince())
    }

    // --- unfreeze shift ---
    @Test
    fun `unfreeze shifts stall next_rent_at forward but leaves null untouched`() {
        stalls.create(stall("s1", nextRentAt = Instant.parse("2026-06-01T10:00:00Z")))
        stalls.create(stall("s2", nextRentAt = null))

        repo.begin(Instant.parse("2026-06-01T10:00:00Z"))
        val result = repo.unfreeze(Duration.ofHours(24).toMillis())

        assertEquals(1, result.stalls)
        assertEquals(Instant.parse("2026-06-02T10:00:00Z"), stalls.findById(StallId("s1"))!!.nextRentAt)
        assertNull(stalls.findById(StallId("s2"))!!.nextRentAt)
    }

    @Test
    fun `unfreeze does not shift stale timestamps on non-OWNED or non-GRACE stalls`() {
        // CR (3694370401): the shift must only touch live OWNED/GRACE rent
        // deadlines. UNOWNED / AUCTIONING / EMERGENCY_AUCTIONING stalls can
        // carry a stale next_rent_at from before a revert; shifting it is
        // meaningless and inflates the reported count.
        stalls.create(stall("s-own", nextRentAt = Instant.parse("2026-06-01T10:00:00Z")))
        stalls.create(
            stall("s-unowned", state = StallState.UNOWNED, nextRentAt = Instant.parse("2026-06-01T10:00:00Z"))
        )
        stalls.create(
            stall("s-auctioning", state = StallState.AUCTIONING, nextRentAt = Instant.parse("2026-06-01T10:00:00Z"))
        )
        stalls.create(
            stall(
                "s-emergency",
                state = StallState.EMERGENCY_AUCTIONING,
                nextRentAt = Instant.parse("2026-06-01T10:00:00Z")
            )
        )

        repo.begin(Instant.parse("2026-06-01T10:00:00Z"))
        val result = repo.unfreeze(Duration.ofHours(24).toMillis())

        assertEquals(1, result.stalls)
        assertEquals(
            Instant.parse("2026-06-02T10:00:00Z"),
            stalls.findById(StallId("s-own"))!!.nextRentAt
        )
        assertEquals(
            Instant.parse("2026-06-01T10:00:00Z"),
            stalls.findById(StallId("s-unowned"))!!.nextRentAt
        )
        assertEquals(
            Instant.parse("2026-06-01T10:00:00Z"),
            stalls.findById(StallId("s-auctioning"))!!.nextRentAt
        )
        assertEquals(
            Instant.parse("2026-06-01T10:00:00Z"),
            stalls.findById(StallId("s-emergency"))!!.nextRentAt
        )
    }

    @Test fun `unfreeze shifts open auction end times but skips sentinel and closed auctions`() {
        val real = Instant.parse("2026-06-01T10:00:00Z")
        auctions.create(auction("auc-open", endAt = real))
        auctions.create(auction("auc-sentinel", endAt = Instant.ofEpochMilli(Long.MAX_VALUE)))
        auctions.create(auction("auc-closed", state = AuctionState.CLOSED, endAt = real))

        repo.begin(real)
        val result = repo.unfreeze(Duration.ofHours(24).toMillis())

        // Only the OPEN auction with a real end time is shifted.
        assertEquals(1, result.auctions)
        assertEquals(real.plus(Duration.ofHours(24)), auctions.findById(AuctionId("auc-open"))!!.endAt)
        assertEquals(Instant.ofEpochMilli(Long.MAX_VALUE), auctions.findById(AuctionId("auc-sentinel"))!!.endAt)
        assertEquals(real, auctions.findById(AuctionId("auc-closed"))!!.endAt)
    }
    @Test
    fun `unfreeze clears the freeze flag`() {
        repo.begin(Instant.parse("2026-06-01T10:00:00Z"))
        repo.unfreeze(Duration.ofHours(1).toMillis())
        assertNull(repo.frozenSince())
    }

    // REQ-303 — begin() self-heals a missing state row (audit L-2). The V026
    // seed row can be deleted; begin() must recreate it, not silently update
    // zero rows and leave the freeze state volatile across restarts.

    @Test
    fun `begin recreates the state row when it was deleted`() {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM maintenance_freeze WHERE id = 1")
            }
        }
        val start = Instant.parse("2026-06-03T08:00:00Z")
        repo.begin(start)
        assertEquals(start, repo.frozenSince())
    }

    @Test fun `unfreeze with zero elapsed is a no-op shift but clears the flag`() {
        repo.begin(Instant.parse("2026-06-01T10:00:00Z"))
        val result = repo.unfreeze(0L)
        assertEquals(0, result.stalls)
        assertEquals(0, result.auctions)
        assertNull(repo.frozenSince())
    }
}
