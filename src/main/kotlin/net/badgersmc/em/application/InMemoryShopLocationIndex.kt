package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopLocationIndex

/**
 * In-memory [ShopLocationIndex] (REQ-282). Application layer: depends on domain + Kotlin stdlib only.
 *
 * Keyed by the shop's container coordinate so the hopper hot path (REQ-281) resolves shop status
 * without a database query. A coordinate may host more than one shop (multiple signs on one chest),
 * so each key maps to a list.
 */
class InMemoryShopLocationIndex : ShopLocationIndex {

    private val byCoord = mutableMapOf<String, MutableList<Shop>>()

    private fun key(world: String, x: Int, y: Int, z: Int): String = "$world:$x:$y:$z"

    private fun key(shop: Shop): String =
        key(shop.containerWorld, shop.containerX, shop.containerY, shop.containerZ)

    override fun shopsAt(world: String, x: Int, y: Int, z: Int): List<Shop> =
        byCoord[key(world, x, y, z)]?.toList() ?: emptyList()

    override fun put(shop: Shop) {
        byCoord.getOrPut(key(shop)) { mutableListOf() }.add(shop)
    }

    override fun remove(shop: Shop) {
        val k = key(shop)
        val list = byCoord[k] ?: return
        // Match by stable id, not full-object equality: a delete may pass a DB-sourced Shop whose
        // non-key fields (e.g. stockCount, updated via updateStock without re-indexing) differ from
        // the indexed copy — equality removal would leave a stale ghost entry. id == 0 means unsaved.
        if (shop.id != 0L) {
            list.removeAll { it.id == shop.id }
        } else {
            list.remove(shop)
        }
        if (list.isEmpty()) byCoord.remove(k)
    }

    override fun rebuild(shops: Collection<Shop>) {
        byCoord.clear()
        shops.forEach(::put)
    }
}
