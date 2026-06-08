package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.events.PostShopTransactionEvent
import net.badgersmc.em.events.ShopStockDepletedEvent
import net.badgersmc.nexus.i18n.LangService
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

/**
 * Monitors container inventory changes and refreshes linked shop sign text (REQ-017).
 * Uses MONITOR priority so it runs after all other handlers.
 */
@Component
class ContainerStockListener(
    private val shopRepository: ShopRepository,
    private val lang: LangService
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTransaction(event: PostShopTransactionEvent) {
        val shop = shopRepository.findById(event.shopId) ?: return
        val signWorld = Bukkit.getWorld(shop.signWorld) ?: return
        val signBlock = signWorld.getBlockAt(shop.signX, shop.signY, shop.signZ)
        val state = signBlock.state as? Sign ?: return
        val trades = recomputeAndPersist(shop)
        updateSignStock(state, trades)
        trackDepletion(shop, trades)
    }

    private var previouslyDepletedShops: MutableSet<Long> = mutableSetOf()

    private fun containerBlockOf(view: org.bukkit.inventory.InventoryView): Block? {
        val top = view.topInventory
        val holder = top.holder
        return when {
            holder is Container -> holder.block
            holder is org.bukkit.block.DoubleChest -> {
                val leftInv = (top as? DoubleChestInventory)?.leftSide
                val rightInv = (top as? DoubleChestInventory)?.rightSide
                val leftBlock = (leftInv?.holder as? Container)?.block
                val rightBlock = (rightInv?.holder as? Container)?.block
                leftBlock ?: rightBlock
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
            val trades = recomputeAndPersist(shop)
            val signWorld = Bukkit.getWorld(shop.signWorld) ?: continue
            val signBlock = signWorld.getBlockAt(shop.signX, shop.signY, shop.signZ)
            val state = signBlock.state as? Sign ?: continue
            updateSignStock(state, trades)
            trackDepletion(shop, trades)
        }
    }

    private fun recomputeAndPersist(shop: net.badgersmc.em.domain.shop.Shop): Int {
        val container = getContainer(shop) ?: return 0
        val rawStock = rawStockOf(container, shop)
        shopRepository.updateStock(shop.id, rawStock)
        return rawStock / shop.sellAmount.coerceAtLeast(1)
    }

    private fun getContainer(shop: net.badgersmc.em.domain.shop.Shop): Container? {
        val containerBlock = Bukkit.getWorld(shop.containerWorld)
            ?.getBlockAt(shop.containerX, shop.containerY, shop.containerZ)
        return containerBlock?.state as? Container
    }

    private fun rawStockOf(container: Container, shop: net.badgersmc.em.domain.shop.Shop): Int {
        val sellStack = ItemStackSerializer.deserialize(shop.sellItem) ?: return 0
        return container.inventory.contents.filterNotNull()
            .filter { it.isSimilar(sellStack) }
            .sumOf { it.amount }
    }

    private fun updateSignStock(state: Sign, trades: Int) {
        state.line(3, lang.msg("container_sign.stock_line", "trades" to trades))
        state.update(true)
    }

    private fun trackDepletion(shop: net.badgersmc.em.domain.shop.Shop, trades: Int) {
        if (trades == 0) {
            if (shop.id !in previouslyDepletedShops) {
                previouslyDepletedShops.add(shop.id)
                Bukkit.getPluginManager().callEvent(ShopStockDepletedEvent(shop.owner))
            }
        } else {
            previouslyDepletedShops.remove(shop.id)
        }
    }
}
