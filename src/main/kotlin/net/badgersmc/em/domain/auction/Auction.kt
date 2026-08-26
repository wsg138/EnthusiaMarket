package net.badgersmc.em.domain.auction

import net.badgersmc.em.domain.stall.StallId
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class Auction(
    val id: AuctionId,
    val stallId: StallId,
    val state: AuctionState,
    val startAt: Instant,
    val endAt: Instant,
    val startingBid: Long,
    val highBid: Bid?,
    val antiSnipeWindow: Duration,
    val antiSnipeExtension: Duration,
    /** Non-zero when the timer should start on the first bid rather than at
     *  creation. Emergency/re-auction flows set this to the auction duration
     *  and ship with [endAt] at the max representable instant; the first bid
     *  sets [endAt] to `now + auctionDuration`. Zero means normal behaviour —
     *  the timer runs from [startAt] regardless of bids. */
    val auctionDuration: Duration = Duration.ZERO,
) {
    init {
        require(startingBid > 0) { "startingBid must be positive, was $startingBid" }
    }

    fun placeBid(bidder: UUID, amount: Long, at: Instant): Auction {
        check(state == AuctionState.OPEN) { "Auction is not open" }
        require(amount >= startingBid) { "Bid must meet starting bid of $startingBid" }
        val current = highBid?.amount ?: (startingBid - 1)
        require(amount > current) { "Bid must exceed current high bid of $current" }

        val newEnd = when {
            // First bid on a deferred-timer auction: start the clock now.
            highBid == null && !auctionDuration.isZero ->
                at.plus(auctionDuration)
            Duration.between(at, endAt) <= antiSnipeWindow ->
                maxOf(endAt, at.plus(antiSnipeExtension))
            else -> endAt
        }

        return copy(
            highBid = Bid(bidder, amount, at),
            endAt = newEnd
        )
    }

    fun close(): Auction = copy(state = AuctionState.CLOSED)
}
