package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.annotations.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.inventory.Inventory

/**
 * Controls hopper access to shop-linked containers (REQ-019).
 * Respects per-shop hopperAllowIn and hopperAllowOut settings.
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
class HopperControlListener(
    private val shopRepository: ShopRepository
) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onHopperMove(event: InventoryMoveItemEvent) {
        val destination = event.destination
        val source = event.source

        // Check source container (hopper pulling from shop container)
        val sourceShops = getShopsForInventory(source)
        if (sourceShops != null) {
            if (sourceShops.any { !it.hopperAllowOut }) {
                event.isCancelled = true
                return
            }
        }

        // Check destination container (hopper pushing into shop container)
        val destShops = getShopsForInventory(destination)
        if (destShops != null) {
            if (destShops.any { !it.hopperAllowIn }) {
                event.isCancelled = true
                return
            }
        }
    }

    private fun getShopsForInventory(inv: Inventory): List<Shop>? {
        // Use Paper's Inventory.getLocation() to avoid the expensive
        // Inventory.getHolder() → BlockEntityState snapshot path that
        // triggers NBT deserialization on every hopper tick (7%+ CPU).
        val loc = inv.location ?: run {
            // Fallback for DoubleChest (getLocation() returns null).
            // DoubleChestHolder check avoids the costly Container→block path for
            // single containers, which are already handled by inv.location above.
            val holder = inv.holder ?: return null
            if (holder !is org.bukkit.block.DoubleChest) return null
            (holder.leftSide as? org.bukkit.block.Container)?.block?.location
                ?: (holder.rightSide as? org.bukkit.block.Container)?.block?.location
                ?: return null
        }
        return shopRepository.findByContainer(
            loc.world?.name ?: "world",
            loc.blockX, loc.blockY, loc.blockZ
        ).ifEmpty { null }
    }
}