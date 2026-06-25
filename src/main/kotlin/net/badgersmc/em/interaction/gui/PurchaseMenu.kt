package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.badgersmc.em.interaction.blockItemTheft
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ContainerTradeResult
import net.badgersmc.em.application.ContainerTradeService
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta

/**
 * Trade GUI for a sign shop. Direction-aware (REQ-006):
 *
 * - **SELL** sign (owner sells): the clicker is the buyer. Button
 *   calls [ContainerTradeService.executeSell] which withdraws
 *   [Shop.costAmount] from the clicker, deposits to the owner, and
 *   moves [Shop.sellAmount] of [Shop.sellItem] from the chest to
 *   the clicker.
 * - **BUY** sign (owner buys): the clicker is the seller. Button
 *   calls [ContainerTradeService.executeBuy] which moves the items
 *   from the clicker into the chest and pays the clicker.
 *
 * Pre-V012 the menu always wired executeBuy, so [SELL] shops were
 * unreachable for actual purchases.
 */
class PurchaseMenu(
    private val shop: Shop,
    private val tradeService: ContainerTradeService,
    private val lang: LangService
) : Menu {

    @Suppress("LongMethod")
    override fun open(player: Player) {
        val gui = ChestGui(
            3,
            ComponentHolder.of(lang.msg("gui.shop.title", "amount" to shop.sellAmount))
        )
        val pane = StaticPane(9, 3)

        val ownerName = Bukkit.getOfflinePlayer(shop.owner).name ?: "Unknown"
        val dirLabel = ShopDisplay.directionLabel(shop.direction)
        val tradesAvailable = ShopDisplay.tradesAvailable(shop)

        pane.addItem(GuiItem(decorated(
            Material.DIAMOND,
            lang.msg("gui.shop.sell_name", "amount" to shop.sellAmount),
            listOf(
                lang.msg("gui.shop.sell_lore_price", "cost" to shop.costAmount),
                lang.msg("gui.shop.sell_lore_direction", "direction" to dirLabel),
                lang.msg("gui.shop.sell_lore_owner", "owner" to ownerName),
                lang.msg("gui.shop.sell_lore_stock", "stock" to tradesAvailable),
            )
        )), 2, 1)

        pane.addItem(GuiItem(decorated(Material.ARROW, lang.msg("gui.shop.arrow_name"))), 4, 1)

        pane.addItem(GuiItem(decorated(
            Material.EMERALD,
            lang.msg("gui.shop.cost_name", "cost" to shop.costAmount)
        )), 6, 1)

        // Direction-aware action button. SELL sign → player buys
        // from owner. BUY sign → player sells to owner. The lang key
        // picks the verb that matches the player's perspective.
        val buttonKey = when (shop.direction) {
            SignDirection.SELL -> "gui.shop.buy_name"
            SignDirection.TRADE -> "gui.shop.trade_name"
            else -> "gui.shop.sell_action_name"
        }
        val buttonLoreKey = when (shop.direction) {
            SignDirection.SELL -> "gui.shop.buy_lore_click"
            SignDirection.TRADE -> "gui.shop.trade_lore_click"
            else -> "gui.shop.sell_action_lore_click"
        }

        pane.addItem(GuiItem(decorated(
            Material.LIME_STAINED_GLASS_PANE,
            lang.msg(buttonKey),
            listOf(lang.msg(buttonLoreKey, "cost" to shop.costAmount))
        )) { event ->
            event.isCancelled = true
            val result = when (shop.direction) {
                SignDirection.SELL -> tradeService.executeSell(shop, player.uniqueId)
                SignDirection.BUY -> tradeService.executeBuy(shop, player.uniqueId)
                SignDirection.TRADE -> tradeService.executeTrade(shop, player.uniqueId)
            }
            when (result) {
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

        // Shulker box preview button (IS2-12, REQ-298)
        addShulkerPreview(pane, player)

        gui.addPane(pane)
        gui.blockItemTheft()
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

    /** IS2-12, REQ-298: add a shulker preview button when the shop sells a shulker box. */
    private fun addShulkerPreview(pane: StaticPane, player: Player) {
        val sellStack = ItemStackSerializer.deserialize(shop.sellItem) ?: return
        if ((sellStack.itemMeta as? BlockStateMeta)?.blockState !is org.bukkit.block.ShulkerBox) return
        pane.addItem(GuiItem(decorated(
            Material.SHULKER_BOX,
            lang.msg("gui.shop.shulker_preview_name"),
            listOf(lang.msg("gui.shop.shulker_preview_lore"))
        )) { event ->
            event.isCancelled = true
            ShulkerPreviewMenu(sellStack.clone(), lang).open(player)
        }, 4, 0)
    }
}