package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.annotations.Component
import org.bukkit.block.Container
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
        val holder = inv.holder ?: return null
        val block = when (holder) {
            is Container -> holder.block
            is org.bukkit.block.DoubleChest -> {
                // Check both halves of the double chest
                val leftBlock = (holder.leftSide as? Container)?.block
                val rightBlock = (holder.rightSide as? Container)?.block
                leftBlock ?: rightBlock ?: return null
            }
            else -> return null
        }
        val loc = block.location
        return shopRepository.findByContainer(
            loc.world?.name ?: "world",
            loc.blockX, loc.blockY, loc.blockZ
        ).ifEmpty { null }
    }
}