package net.badgersmc.em.domain.shop

import java.util.UUID

/** Average price stats for a sell item over a time window. */
data class PriceStats(val avgPrice: Double, val sampleCount: Int)

/** Aggregated price-change data for the search results ticker icon. */
data class PriceTicker(
    val avgPrice: Double,
    val sampleCount: Int,
    val change24h: Double?,
    val change7d: Double?,
    val change30d: Double?,
)

interface ShopTransactionRepository {
    fun record(tx: ShopTransaction): ShopTransaction
    /** Newest-first, paged. */
    fun findByOwner(owner: UUID, limit: Int, offset: Int): List<ShopTransaction>
    /** Transactions where player was owner OR buyer (for members). */
    fun findByOwnerOrBuyer(player: UUID, limit: Int, offset: Int): List<ShopTransaction>
    fun countUnnotified(owner: UUID): Int
    fun markNotified(owner: UUID)
    /** Delete rows older than [beforeMs]; returns rows removed. */
    fun prune(beforeMs: Long): Int
    /** Average sell price for [item] between [fromMs] (inclusive) and [toMs] (exclusive). */
    fun avgPriceInWindow(item: String, fromMs: Long, toMs: Long): PriceStats?
}
