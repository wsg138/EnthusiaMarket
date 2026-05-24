package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.events.ShopStockDepletedEvent
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger

/**
 * Monitors container inventory changes and refreshes linked shop sign text (REQ-017).
 * Uses MONITOR priority so it runs after all other handlers.
 */
@Component
class ContainerStockListener(
    private val shopRepository: ShopRepository,
    private val logger: Logger
) : Listener {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin ?: return
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        val containerBlock = containerBlockOf(event.view) ?: return
        refreshShopsAt(containerBlock)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        val containerBlock = containerBlockOf(event.view) ?: return
        refreshShopsAt(containerBlock)
    }

    private fun containerBlockOf(view: org.bukkit.inventory.InventoryView): Block? {
        val top = view.topInventory
        val holder = top.holder
        return when {
            holder is Container -> holder.block
            holder is org.bukkit.block.DoubleChest -> {
                val leftInv = (top as? DoubleChestInventory)?.leftSide ?: return null
                val leftHolder = leftInv.holder
                if (leftHolder is Container) leftHolder.block else null
            }
            else -> null
        }
    }

    private fun refreshShopsAt(containerBlock: Block) {
        val loc = containerBlock.location
        val shops = shopRepository.findByContainer(
            loc.world?.name ?: "world",
            loc.blockX, loc.blockY, loc.blockZ
        )
        for (shop in shops) {
            // Find and update the sign block
            val signWorld = Bukkit.getWorld(shop.signWorld) ?: continue
            val signBlock = signWorld.getBlockAt(shop.signX, shop.signY, shop.signZ)
            val state = signBlock.state
            if (state is Sign) {
                // Compute available trades
                val containerBlock2 = Bukkit.getWorld(shop.containerWorld)
                    ?.getBlockAt(shop.containerX, shop.containerY, shop.containerZ)
                val containerState = containerBlock2?.state
                val trades = if (containerState is Container) {
                    computeTradesAvailable(shop, containerState)
                } else 0

                // Update line 3 of the sign with stock info
                state.setLine(3, "§7Stock: $trades")
                state.update(true)

                // Fire event when stock reaches zero (REQ-026)
                if (trades == 0) {
                    Bukkit.getPluginManager()?.callEvent(ShopStockDepletedEvent(shop.owner))
                }
            }
        }
    }

    private fun computeTradesAvailable(
        shop: net.badgersmc.em.domain.shop.Shop,
        container: Container
    ): Int {
        val sellStack = ItemStackSerializer.deserialize(shop.sellItem) ?: return 0
        sellStack.amount = shop.sellAmount
        var count = 0
        for (item in container.inventory.contents) {
            if (item != null && item.isSimilar(sellStack)) {
                count += item.amount / shop.sellAmount
            }
        }
        return count
    }
}