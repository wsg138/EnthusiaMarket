package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ContainerTradeResult
import net.badgersmc.em.application.ContainerTradeService
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
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
    private val tradeService: ContainerTradeService,
    private val lang: LangService
) : Menu {

    override fun open(player: Player) {
        val gui = ChestGui(
            3,
            ComponentHolder.of(lang.msg("gui.shop.title", "amount" to shop.sellAmount))
        )
        val pane = StaticPane(9, 3)

        pane.addItem(GuiItem(decorated(
            Material.DIAMOND,
            lang.msg("gui.shop.sell_name", "amount" to shop.sellAmount),
            listOf(lang.msg("gui.shop.sell_lore_price", "cost" to shop.costAmount))
        )), 2, 1)

        pane.addItem(GuiItem(decorated(Material.ARROW, lang.msg("gui.shop.arrow_name"))), 4, 1)

        pane.addItem(GuiItem(decorated(
            Material.EMERALD,
            lang.msg("gui.shop.cost_name", "cost" to shop.costAmount)
        )), 6, 1)

        pane.addItem(GuiItem(decorated(
            Material.LIME_STAINED_GLASS_PANE,
            lang.msg("gui.shop.buy_name"),
            listOf(lang.msg("gui.shop.buy_lore_click"))
        )) { event ->
            event.isCancelled = true
            when (val result = tradeService.executeBuy(shop, player.uniqueId)) {
                is ContainerTradeResult.Success -> player.sendMessage(
                    lang.msg("shop.trade.success", "message" to result.message)
                )
                is ContainerTradeResult.Failure -> player.sendMessage(
                    lang.msg("shop.trade.failure", "reason" to result.reason)
                )
                is ContainerTradeResult.CompensationFailed -> {
                    player.sendMessage(lang.msg("shop.trade.compensation_failed", "error" to result.error))
                    player.sendMessage(lang.msg("shop.trade.compensation_note", "compensation" to result.compensation))
                }
            }
        }, 4, 2)

        gui.addPane(pane)
        gui.show(player)
    }

    private fun decorated(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name)
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }
}
