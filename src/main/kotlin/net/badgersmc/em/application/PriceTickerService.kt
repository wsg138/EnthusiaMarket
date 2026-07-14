package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.PriceStats
import net.badgersmc.em.domain.shop.PriceTicker
import net.badgersmc.em.domain.shop.ShopTransactionRepository

/** Shared price-ticker computation used by `/shop search` and `/finditem`. */
object PriceTickerService {
    fun compute(item: String, transactions: ShopTransactionRepository): PriceTicker? {
        val now = System.currentTimeMillis()
        val dayMs = 24 * 60 * 60 * 1000L
        val current = transactions.avgPriceInWindow(item, now - dayMs, now) ?: return null
        fun change(days: Int, cachedCur: PriceStats = current): Double? {
            val winMs = days * dayMs
            val cur = if (days == 1) cachedCur
                else transactions.avgPriceInWindow(item, now - winMs, now)
            val prev = transactions.avgPriceInWindow(item, now - 2 * winMs, now - winMs)
            if (cur == null || prev == null || prev.avgPrice <= 0) return null
            return ((cur.avgPrice - prev.avgPrice) / prev.avgPrice) * 100
        }
        return PriceTicker(
            avgPrice = current.avgPrice,
            sampleCount = current.sampleCount,
            change24h = change(1),
            change7d = change(7),
            change30d = change(30),
        )
    }
}
