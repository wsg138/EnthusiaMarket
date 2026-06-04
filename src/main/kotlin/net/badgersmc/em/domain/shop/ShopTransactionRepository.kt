package net.badgersmc.em.domain.shop

import java.util.UUID

interface ShopTransactionRepository {
    fun record(tx: ShopTransaction): ShopTransaction
    /** Newest-first, paged. */
    fun findByOwner(owner: UUID, limit: Int, offset: Int): List<ShopTransaction>
    fun countUnnotified(owner: UUID): Int
    fun markNotified(owner: UUID)
    /** Delete rows older than [beforeMs]; returns rows removed. */
    fun prune(beforeMs: Long): Int
}
