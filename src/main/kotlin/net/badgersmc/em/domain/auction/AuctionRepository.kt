package net.badgersmc.em.domain.auction

import net.badgersmc.em.domain.stall.StallId

interface AuctionRepository {
    fun findById(id: AuctionId): Auction?
    fun findOpenByStall(stallId: StallId): Auction?
    fun allOpen(): List<Auction>
    fun findExpired(): List<Auction>
    fun create(auction: Auction)
    fun save(auction: Auction)
    fun delete(id: AuctionId)
    /** Most recent CLOSED auction for [stallId] by end_at desc, or null. */
    fun findMostRecentClosedByStall(stallId: StallId): Auction?
}
