package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopLocationIndex
import net.badgersmc.em.domain.shop.ShopRepository
import java.util.UUID

/**
 * [ShopRepository] decorator (REQ-281/282) that keeps a [ShopLocationIndex] in lockstep with the
 * persisted state and serves the hopper hot path's [findByContainer] from memory. Application layer:
 * the single choke point through which every shop mutation flows (decision: docs/tasks.md PERF-2).
 *
 * Reads other than [findByContainer] pass straight through; mutations delegate and then reconcile the
 * index. [updateStock] does not touch the index — no [findByContainer] consumer relies on the cached
 * `stockCount` (ContainerStockListener recomputes stock from the live container), so the cached copy
 * staying at its last-indexed stock is harmless and avoids a read on the trade path.
 */
@Suppress("TooManyFunctions")
class IndexedShopRepository(
    private val delegate: ShopRepository,
    private val index: ShopLocationIndex,
) : ShopRepository {

    /** Replace any prior index entry for [shop]'s id at its container coordinate, then index it. */
    private fun reindex(shop: Shop) {
        index.shopsAt(shop.containerWorld, shop.containerX, shop.containerY, shop.containerZ)
            .firstOrNull { it.id == shop.id }
            ?.let { index.remove(it) }
        index.put(shop)
    }

    override fun upsert(shop: Shop): Shop {
        val persisted = delegate.upsert(shop)
        reindex(persisted)
        return persisted
    }

    override fun setGuildOwnership(id: Long, guildId: UUID, creatorId: UUID): Shop? =
        delegate.setGuildOwnership(id, guildId, creatorId)?.also(::reindex)

    override fun removeGuildOwnership(id: Long): Shop? =
        delegate.removeGuildOwnership(id)?.also(::reindex)

    override fun delete(id: Long) {
        val shop = delegate.findById(id)
        delegate.delete(id)
        shop?.let { index.remove(it) }
    }

    override fun deleteByContainer(world: String, x: Int, y: Int, z: Int) {
        delegate.deleteByContainer(world, x, y, z)
        index.shopsAt(world, x, y, z).forEach { index.remove(it) }
    }

    override fun deleteByOwner(owner: UUID): Int {
        val owned = delegate.findByOwner(owner)
        val removed = delegate.deleteByOwner(owner)
        owned.forEach { index.remove(it) }
        return removed
    }

    override fun updateStock(id: Long, stockCount: Int) = delegate.updateStock(id, stockCount)

    override fun findByContainer(world: String, x: Int, y: Int, z: Int): List<Shop> =
        index.shopsAt(world, x, y, z)

    // --- pass-through reads ---
    override fun findById(id: Long): Shop? = delegate.findById(id)
    override fun findBySign(world: String, x: Int, y: Int, z: Int): Shop? = delegate.findBySign(world, x, y, z)
    override fun findByStall(stallId: String): List<Shop> = delegate.findByStall(stallId)
    override fun findByOwner(owner: UUID): List<Shop> = delegate.findByOwner(owner)
    override fun findByGuildId(guildId: UUID): List<Shop> = delegate.findByGuildId(guildId)
    override fun all(): List<Shop> = delegate.all()
    override fun countAll(): Int = delegate.countAll()
    override fun countByOwner(owner: UUID): Int = delegate.countByOwner(owner)
    override fun findBySellMaterial(material: String): List<Shop> = delegate.findBySellMaterial(material)
    override fun backfillSellMaterials(): Int = delegate.backfillSellMaterials()
}
