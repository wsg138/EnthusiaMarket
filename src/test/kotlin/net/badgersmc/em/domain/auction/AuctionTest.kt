package net.badgersmc.em.domain.auction

import net.badgersmc.em.domain.stall.StallId
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuctionTest {
    private val now = Instant.parse("2026-05-15T10:00:00Z")
    private val later = now.plus(Duration.ofHours(24))

    private fun freshAuction() = Auction(
        id = AuctionId("auc_1"),
        stallId = StallId("stall_01"),
        state = AuctionState.OPEN,
        startAt = now,
        endAt = later,
        startingBid = 100L,
        highBid = null,
        antiSnipeWindow = Duration.ofMinutes(10)
    )

    @Test fun `first bid must exceed starting bid`() {
        val a = freshAuction()
        val bidder = UUID.randomUUID()
        val placed = a.placeBid(bidder = bidder, amount = 150L, at = now.plus(Duration.ofMinutes(1)))
        assertEquals(150L, placed.highBid?.amount)
        assertEquals(bidder, placed.highBid?.bidder)
    }

    @Test fun `bid below starting bid is rejected`() {
        val a = freshAuction()
        assertFailsWith<IllegalArgumentException> {
            a.placeBid(UUID.randomUUID(), 50L, at = now.plus(Duration.ofMinutes(1)))
        }
    }

    @Test fun `bid must strictly exceed current high`() {
        val a = freshAuction()
            .placeBid(UUID.randomUUID(), 200L, at = now.plus(Duration.ofMinutes(1)))
        assertFailsWith<IllegalArgumentException> {
            a.placeBid(UUID.randomUUID(), 200L, at = now.plus(Duration.ofMinutes(2)))
        }
    }

    @Test fun `bid inside anti-snipe window extends endAt`() {
        val a = freshAuction()
        val bidTime = later.minus(Duration.ofMinutes(5))
        val placed = a.placeBid(UUID.randomUUID(), 200L, at = bidTime)
        assertEquals(bidTime.plus(Duration.ofMinutes(10)), placed.endAt)
    }

    @Test fun `bid outside anti-snipe window does not extend endAt`() {
        val a = freshAuction()
        val bidTime = now.plus(Duration.ofHours(1))
        val placed = a.placeBid(UUID.randomUUID(), 200L, at = bidTime)
        assertEquals(later, placed.endAt)
    }

    @Test fun `bids rejected after auction has closed`() {
        val a = freshAuction().copy(state = AuctionState.CLOSED)
        assertFailsWith<IllegalStateException> {
            a.placeBid(UUID.randomUUID(), 200L, at = now.plus(Duration.ofMinutes(1)))
        }
    }

    @Test fun `close marks auction CLOSED`() {
        val a = freshAuction()
            .placeBid(UUID.randomUUID(), 200L, at = now.plus(Duration.ofMinutes(1)))
            .close()
        assertEquals(AuctionState.CLOSED, a.state)
        assertTrue(a.highBid != null)
    }
}
