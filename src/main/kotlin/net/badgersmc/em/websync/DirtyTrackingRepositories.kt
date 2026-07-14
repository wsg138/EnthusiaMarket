package net.badgersmc.em.websync

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun interface WebsiteSyncDirtySink {
    fun markDirty(stallId: String)
}

class DirtyTrackingStallRepository(
    private val delegate: StallRepository,
    private val dirty: WebsiteSyncDirtySink,
) : StallRepository by delegate {
    override fun save(stall: Stall) {
        delegate.save(stall)
        notify(stall.id.value)
    }

    override fun create(stall: Stall) {
        delegate.create(stall)
        notify(stall.id.value)
    }

    private fun notify(stallId: String) {
        try { dirty.markDirty(stallId) } catch (_: Exception) { /* Market mutations must remain isolated. */ }
    }
}

@Suppress("TooManyFunctions")
class DirtyTrackingShopRepository(
    private val delegate: ShopRepository,
    private val dirty: WebsiteSyncDirtySink,
) : ShopRepository by delegate {
    private val stallByShopId = ConcurrentHashMap<Long, String>()

    init {
        runCatching { delegate.all().forEach { stallByShopId[it.id] = it.stallId } }
    }

    override fun upsert(shop: Shop): Shop {
        val oldStall = stallId(shop.id)
        val persisted = delegate.upsert(shop)
        stallByShopId[persisted.id] = persisted.stallId
        notify(oldStall, persisted.stallId)
        return persisted
    }

    override fun delete(id: Long) {
        val stall = stallId(id)
        delegate.delete(id)
        stallByShopId.remove(id)
        notify(stall)
    }

    override fun deleteByContainer(world: String, x: Int, y: Int, z: Int) {
        val indexed = safe { delegate.findByContainer(world, x, y, z) }.orEmpty()
        val persisted = safe {
            delegate.all().filter {
                it.containerWorld == world && it.containerX == x && it.containerY == y && it.containerZ == z
            }
        }.orEmpty()
        val affected = (indexed + persisted).distinctBy { it.id }.map { it.id to it.stallId }
        delegate.deleteByContainer(world, x, y, z)
        affected.forEach { stallByShopId.remove(it.first) }
        notify(*affected.map { it.second }.toTypedArray())
    }

    override fun deleteByOwner(owner: UUID): Int {
        val affected = safe { delegate.findByOwner(owner).map { it.id to it.stallId } }.orEmpty()
        val count = delegate.deleteByOwner(owner)
        affected.forEach { stallByShopId.remove(it.first) }
        notify(*affected.map { it.second }.toTypedArray())
        return count
    }

    override fun updateStock(id: Long, stockCount: Int) {
        val stall = stallId(id)
        delegate.updateStock(id, stockCount)
        notify(stall)
    }

    override fun updateStockBatch(batch: Map<Long, Int>) {
        val stalls = batch.keys.mapNotNull(::stallId).distinct()
        delegate.updateStockBatch(batch)
        notify(*stalls.toTypedArray())
    }

    override fun freezeByStall(stallId: String, frozen: Boolean) {
        delegate.freezeByStall(stallId, frozen)
        notify(stallId)
    }

    private fun stallId(id: Long): String? = stallByShopId[id]
        ?: safe { delegate.findById(id)?.stallId }?.also { stallByShopId[id] = it }

    private fun notify(vararg ids: String?) {
        ids.filterNotNull().distinct().forEach {
            try { dirty.markDirty(it) } catch (_: Exception) { /* Never escape into Market callers. */ }
        }
    }

    private fun <T> safe(block: () -> T): T? = try { block() } catch (_: Exception) { null }
}
