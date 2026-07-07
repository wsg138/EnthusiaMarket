package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionState
import net.badgersmc.em.domain.auction.Bid
import net.badgersmc.em.domain.stall.StallId
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuctionRepositorySqlTest {
    private lateinit var ds: HikariDataSource
    private lateinit var repo: AuctionRepositorySql

    @BeforeTest fun setUp() {
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite::memory:"
            maximumPoolSize = 1
        })
        net.badgersmc.nexus.persistence.MigrationRunner(ds, "migrations", this::class.java.classLoader).runAll()
        repo = AuctionRepositorySql(ds)
    }

    @AfterTest fun tearDown() { ds.close() }

    private fun sampleAuction(
        id: String = "auc-0001",
        stallId: String = "stall_01",
        startingBid: Long = 100L,
        state: AuctionState = AuctionState.OPEN,
        highBid: Bid? = null,
        startAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        endAt: Instant = Instant.parse("2026-01-02T00:00:00Z"),
        antiSnipeWindow: Duration = Duration.ofMinutes(1),
        antiSnipeExtension: Duration = Duration.ofMinutes(1)
    ) = Auction(
        id = AuctionId(id),
        stallId = StallId(stallId),
        state = state,
        startAt = startAt,
        endAt = endAt,
        startingBid = startingBid,
        highBid = highBid,
        antiSnipeWindow = antiSnipeWindow,
        antiSnipeExtension = antiSnipeExtension
    )

    @Test fun `findById returns null for unknown id`() {
        assertNull(repo.findById(AuctionId("nope")))
    }

    @Test fun `create then findById round-trips`() {
        val a = sampleAuction()
        repo.create(a)
        val found = repo.findById(AuctionId("auc-0001"))
        assertNotNull(found)
        assertEquals(a.id, found.id)
        assertEquals(a.stallId, found.stallId)
        assertEquals(a.state, found.state)
        assertEquals(a.startAt, found.startAt)
        assertEquals(a.endAt, found.endAt)
        assertEquals(a.startingBid, found.startingBid)
        assertEquals(a.highBid, found.highBid)
        assertEquals(a.antiSnipeWindow, found.antiSnipeWindow)
        assertEquals(a.antiSnipeExtension, found.antiSnipeExtension)
    }

    @Test fun `create then findById with a high bid`() {
        val bidder = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val bidAt = Instant.parse("2026-01-01T12:00:00Z")
        val bid = Bid(bidder, 500L, bidAt)
        val a = sampleAuction(highBid = bid)
        repo.create(a)
        val found = repo.findById(AuctionId("auc-0001"))
        assertNotNull(found)
        assertEquals(bid, found.highBid)
        assertEquals(500L, found.highBid!!.amount)
        assertEquals(bidder, found.highBid!!.bidder)
    }

    @Test fun `allOpen returns only open auctions whose end time hasn't passed`() {
        val future = Instant.parse("2030-01-01T00:00:00Z")
        val past = Instant.parse("2020-01-01T00:00:00Z")
        repo.create(sampleAuction(id = "auc-001", endAt = future))        // open + future
        repo.create(sampleAuction(id = "auc-002", endAt = past))          // open + expired
        repo.create(sampleAuction(id = "auc-003", state = AuctionState.CLOSED, endAt = future)) // closed
        repo.create(sampleAuction(id = "auc-004", state = AuctionState.CANCELLED, endAt = future)) // cancelled

        val open = repo.allOpen()
        assertEquals(1, open.size)
        assertEquals("auc-001", open[0].id.value)
    }

    @Test fun `findOpenByStall returns open auction for a given stall`() {
        val future = Instant.parse("2030-01-01T00:00:00Z")
        repo.create(sampleAuction(id = "auc-001", stallId = "stall_01", endAt = future))
        repo.create(sampleAuction(id = "auc-002", stallId = "stall_01", endAt = future, state = AuctionState.CLOSED))

        val found = repo.findOpenByStall(StallId("stall_01"))
        assertNotNull(found)
        assertEquals("auc-001", found.id.value)
    }

    @Test fun `findOpenByStall returns null if no open auction exists`() {
        assertNull(repo.findOpenByStall(StallId("stall_01")))
    }

    @Test fun `save updates auction fields`() {
        val a = sampleAuction(endAt = Instant.parse("2030-01-01T00:00:00Z"))
        repo.create(a)

        val bidder = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val bidAt = Instant.parse("2030-01-01T06:00:00Z")
        val updated = a.placeBid(bidder, 200L, bidAt)
        repo.save(updated)

        val found = repo.findById(AuctionId("auc-0001"))!!
        assertEquals(200L, found.highBid!!.amount)
        assertEquals(bidder, found.highBid!!.bidder)
    }

    @Test fun `save changes status to CLOSED`() {
        val a = sampleAuction(endAt = Instant.parse("2030-01-01T00:00:00Z"))
        repo.create(a)

        val closed = a.close()
        repo.save(closed)

        val found = repo.findById(AuctionId("auc-0001"))!!
        assertEquals(AuctionState.CLOSED, found.state)
    }

    @Test fun `findExpired returns open auctions past their end time`() {
        val past = Instant.parse("2020-01-01T00:00:00Z")
        val future = Instant.parse("2030-01-01T00:00:00Z")
        repo.create(sampleAuction(id = "auc-001", endAt = past))           // expired open
        repo.create(sampleAuction(id = "auc-002", endAt = future))         // not expired
        repo.create(sampleAuction(id = "auc-003", state = AuctionState.CLOSED, endAt = past)) // closed

        val expired = repo.findExpired()
        assertEquals(1, expired.size)
        assertEquals("auc-001", expired[0].id.value)
    }

    @Test fun `delete removes auction`() {
        repo.create(sampleAuction())
        repo.delete(AuctionId("auc-0001"))
        assertNull(repo.findById(AuctionId("auc-0001")))
    }
}