package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.annotations.Service
import java.util.UUID

/**
 * Player-facing shop management operations (ItemShops parity sub-project 1):
 * list / trust / untrust / delete over the owner's shops. All mutations are
 * ownership-checked against the actor; menus are a convenience layer on top.
 */
@Service
class ShopManagementService(
    private val shopRepository: ShopRepository,
) {
    private val log = java.util.logging.Logger.getLogger(ShopManagementService::class.java.name)

    fun shopsOwnedBy(owner: UUID): List<Shop> = shopRepository.findByOwner(owner)

    /** Trust [target] on each of [shopIds] the [actor] actually owns. Returns count changed. */
    fun trust(actor: UUID, target: UUID, shopIds: List<Long>): Int =
        mutateOwned(actor, shopIds) { it.copy(trusted = it.trusted + target) }

    /** Untrust [target] on each of [shopIds] the [actor] owns. Returns count changed. */
    fun untrust(actor: UUID, target: UUID, shopIds: List<Long>): Int =
        mutateOwned(actor, shopIds) { it.copy(trusted = it.trusted - target) }

    fun trustAll(actor: UUID, target: UUID): Int =
        trust(actor, target, shopsOwnedBy(actor).map { it.id })

    fun untrustAll(actor: UUID, target: UUID): Int =
        untrust(actor, target, shopsOwnedBy(actor).map { it.id })

    /** Delete a single shop if [actor] owns it. Returns true when deleted. */
    fun delete(actor: UUID, shopId: Long): Boolean {
        val shop = shopRepository.findById(shopId) ?: return false
        if (shop.owner != actor) return false
        shopRepository.delete(shopId)
        fireShopDeleted(shop.owner)
        return true
    }

    /** Delete every shop [actor] owns. Returns count deleted. */
    fun deleteAll(actor: UUID): Int {
        val owned = shopsOwnedBy(actor)
        owned.forEach {
            shopRepository.delete(it.id)
            fireShopDeleted(it.owner)
        }
        return owned.size
    }

    /** Delete a shop regardless of owner (admin tooling, SP5). Returns true when deleted. */
    fun adminDelete(shopId: Long): Boolean {
        val shop = shopRepository.findById(shopId) ?: return false
        shopRepository.delete(shopId)
        fireShopDeleted(shop.owner)
        return true
    }

    /**
     * Fire [ShopDeletedEvent] so listeners (analytics, advancement hooks, sign
     * cleanup) react to command/menu/breakdelete deletes the same way they do to
     * container-break deletes. Null-safe + best-effort: `getServer()` is null in
     * unit tests (no event fired, no NPE), mirroring AuctionLifecycleService.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun fireShopDeleted(owner: UUID) {
        try {
            org.bukkit.Bukkit.getServer()?.pluginManager?.callEvent(
                net.badgersmc.em.events.ShopDeletedEvent(owner)
            )
        } catch (e: Exception) {
            log.warning("Failed to fire ShopDeletedEvent: ${e.message}")
        }
    }

    private fun mutateOwned(actor: UUID, shopIds: List<Long>, edit: (Shop) -> Shop): Int {
        var changed = 0
        for (id in shopIds) {
            val shop = shopRepository.findById(id) ?: continue
            if (shop.owner != actor) continue
            val updated = edit(shop)
            if (updated != shop) {
                shopRepository.upsert(updated)
                changed++
            }
        }
        return changed
    }
}