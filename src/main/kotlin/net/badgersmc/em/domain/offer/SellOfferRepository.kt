package net.badgersmc.em.domain.offer

import net.badgersmc.em.domain.stall.StallId

/**
 * Repository for [SellOffer] aggregates. Each stall has at most one
 * open offer at a time — [findByStall] is the primary lookup.
 */
interface SellOfferRepository {
    fun findByStall(stallId: StallId): SellOffer?
    fun all(): List<SellOffer>
    fun save(offer: SellOffer)
    fun delete(stallId: StallId)
}
