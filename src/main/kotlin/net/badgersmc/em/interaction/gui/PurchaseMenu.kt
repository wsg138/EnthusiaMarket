package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ContainerTradeResult
import net.badgersmc.em.application.ContainerTradeService
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.interaction.Menu
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * IFramework ChestGui showing the shop's sell/cost items and trade buttons (REQ-013).
 *
 * BUY executes an actual trade via [ContainerTradeService.executeBuy].
 */
class PurchaseMenu(
    private val shop: Shop,
    private val tradeService: ContainerTradeService
) : Menu {

    override fun open(player: Player) {
        // Create a 3-row GUI
        val gui = ChestGui(3, "§8Shop — ${shop.sellAmount}x Item")

        val pane = StaticPane(9, 3)

        // Sell item display (center-left, slot 11)
        val sellStack = ItemStack(Material.DIAMOND) // placeholder — real item from TDD-53
        val sellMeta = sellStack.itemMeta ?: return
        sellMeta.setDisplayName("§e§lSELL: §f${shop.sellAmount}x Item")
        sellMeta.lore = listOf("§7Price: §6${shop.costAmount}x per trade")
        sellStack.itemMeta = sellMeta

        // Arrow (center, slot 13)
        val arrow = ItemStack(Material.ARROW)
        val arrowMeta = arrow.itemMeta ?: return
        arrowMeta.setDisplayName("§7→")
        arrow.itemMeta = arrowMeta

        // Cost item display (center-right, slot 15)
        val costStack = ItemStack(Material.EMERALD) // placeholder — real cost item from TDD-53
        val costMeta = costStack.itemMeta ?: return
        costMeta.setDisplayName("§6§lCOST: §f${shop.costAmount}x per trade")
        costStack.itemMeta = costMeta

        // Buy button (bottom center, slot 22)
        val buyStack = ItemStack(Material.LIME_STAINED_GLASS_PANE)
        val buyMeta = buyStack.itemMeta ?: return
        buyMeta.setDisplayName("§a§lBUY")
        buyMeta.lore = listOf("§7Click to purchase")
        buyStack.itemMeta = buyMeta

        pane.addItem(GuiItem(sellStack), 2, 1) // slot 11
        pane.addItem(GuiItem(arrow), 4, 1)     // slot 13
        pane.addItem(GuiItem(costStack), 6, 1) // slot 15
        pane.addItem(GuiItem(buyStack, { event ->
            event.isCancelled = true
            when (val result = tradeService.executeBuy(shop, player.uniqueId)) {
                is ContainerTradeResult.Success -> player.sendMessage("§a[Shop] ${result.message}")
                is ContainerTradeResult.Failure -> player.sendMessage("§c[Shop] ${result.reason}")
                is ContainerTradeResult.CompensationFailed -> {
                    player.sendMessage("§c[Shop] Trade failed: ${result.error}")
                    player.sendMessage("§7  Compensation: ${result.compensation}")
                }
            }
        }), 4, 2) // slot 22

        gui.addPane(pane)
        gui.show(player)
    }
}