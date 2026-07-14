package net.badgersmc.em.websync

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

fun interface WebsiteSyncDirtySink {
    fun markDirty(stallId: String)
}

fun interface DirtyTrackingFailureObserver {
    fun report(operation: String, publicStallId: String?)
}

class RateLimitedDirtyTrackingFailureObserver(
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minimumIntervalMillis: Long = 60_000,
) : DirtyTrackingFailureObserver {
    private val lastReport = ConcurrentHashMap<String, Long>()

    override fun report(operation: String, publicStallId: String?) {
        val key = "$operation:${publicStallId.orEmpty()}"
        val now = clock()
        val prior = lastReport[key]
        if (prior == null || now - prior >= minimumIntervalMillis) {
            lastReport[key] = now
            logger.warning("Website sync dirty tracking failed (category=$operation, stall=${publicStallId ?: "unknown"})")
        }
    }
}

/**
 * Decorates [StallRepository] to notify [WebsiteSyncDirtySink] on save/create.
 *
 * Stalls are never truly deleted in this domain — ownership changes via sellback,
 * eviction clears the owner, and termination sets state=TERMINATED. All of these
 * flow through [save], so no [delete] overrides are needed. If a true hard-delete
 * method is added to [StallRepository] in the future, it must be overridden here
 * to fire [dirty.markDirty].
 */
class DirtyTrackingStallRepository(
    private val delegate: StallRepository,
    private val dirty: WebsiteSyncDirtySink,
    private val failures: DirtyTrackingFailureObserver = DirtyTrackingFailureObserver { _, _ -> },
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
        try { dirty.markDirty(stallId) } catch (_: Exception) { failures.report("notify", stallId) }
    }
}

@Suppress("TooManyFunctions")
class DirtyTrackingShopRepository(
    private val delegate: ShopRepository,
    private val dirty: WebsiteSyncDirtySink,
    private val failures: DirtyTrackingFailureObserver = DirtyTrackingFailureObserver { _, _ -> },
) : ShopRepository by delegate {
    private val stallByShopId = ConcurrentHashMap<Long, String>()

    init {
        safe("initial_load") { delegate.all().forEach { stallByShopId[it.id] = it.stallId } }
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
        val affected = safe("lookup_delete_by_container") { delegate.findByContainer(world, x, y, z) }
            .orEmpty()
            .map { it.id to it.stallId }
        delegate.deleteByContainer(world, x, y, z)
        affected.forEach { stallByShopId.remove(it.first) }
        notify(*affected.map { it.second }.toTypedArray())
    }

    override fun deleteByOwner(owner: UUID): Int {
        val affected = safe("lookup_delete_by_owner") { delegate.findByOwner(owner).map { it.id to it.stallId } }.orEmpty()
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
        ?: safe("lookup_shop") { delegate.findById(id)?.stallId }?.also { stallByShopId[id] = it }

    private fun notify(vararg ids: String?) {
        ids.filterNotNull().distinct().forEach {
            try { dirty.markDirty(it) } catch (_: Exception) { failures.report("notify", it) }
        }
    }

    private fun <T> safe(operation: String, block: () -> T): T? = try { block() } catch (_: Exception) {
        failures.report(operation, null)
        null
    }
}
