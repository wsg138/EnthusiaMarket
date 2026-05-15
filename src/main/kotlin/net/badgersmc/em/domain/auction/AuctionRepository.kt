package net.badgersmc.em.domain.auction

import net.badgersmc.em.domain.stall.StallId

interface AuctionRepository {
    fun findById(id: AuctionId): Auction?
    fun findOpenByStall(stallId: StallId): Auction?
    fun allOpen(): List<Auction>
    fun create(auction: Auction)
    fun save(auction: Auction)
}
