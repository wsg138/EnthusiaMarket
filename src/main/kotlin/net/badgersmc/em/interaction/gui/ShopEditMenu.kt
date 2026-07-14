package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.badgersmc.em.interaction.blockItemTheft
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.badgersmc.em.interaction.MenuItems
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Owner edit GUI for a sign shop (ItemShops parity sub-project 1). Edits the sell
 * item + amount, the cost (money) amount, hopper in/out, freeze, and delete. Trust
 * is managed via `/shop trust`. Under EM's current money model the cost is a number;
 * sub-project 3 (barter) adds an item-cost mode here.
 */
class ShopEditMenu(
    private val shop: Shop,
    private val shopRepository: ShopRepository,
    private val management: ShopManagementService,
    private val lang: LangService,
) : Menu {

    private var sellItemB64: String = shop.sellItem
    private var sellAmount: Int = shop.sellAmount
    private var costItemB64: String = shop.costItem
    private var costAmount: Int = shop.costAmount
    private var hopperIn: Boolean = shop.hopperAllowIn
    private var hopperOut: Boolean = shop.hopperAllowOut
    private var frozen: Boolean = shop.frozen
    private var searchEnabled: Boolean = shop.searchEnabled

    override fun open(player: Player) {
        if (player.uniqueId != shop.owner &&
            !player.hasPermission("enthusiamarket.admin") &&
            !player.hasPermission("enthusiamarket.admin.shop")
        ) {
            player.sendMessage(lang.msg("shop.edit.not_owner"))
            return
        }
        render(player)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun render(player: Player) {
        val gui = ChestGui(3, ComponentHolder.of(lang.msg("gui.shop.edit.title")))
        val pane = StaticPane(9, 3)

        // Direction + stock info (from draft sellAmount when item unchanged).
        val dirLabel = ShopDisplay.directionLabel(shop.direction)
        val tradesAvailable = if (sellItemB64 == shop.sellItem)
            if (sellAmount > 0) shop.stockCount / sellAmount else 0
        else
            null
        val stockLore = if (tradesAvailable != null)
            listOf(lang.msg("gui.shop.edit.stock", "stock" to tradesAvailable))
        else
            listOf(lang.msg("gui.shop.edit.stock_unknown"))
        pane.addItem(GuiItem(decorated(
            Material.OAK_SIGN,
            lang.msg("gui.shop.edit.direction", "direction" to dirLabel),
            stockLore
        )), 0, 1)

        // Sell item preview (decoded). Clicking sets the sell item to the item in hand.
        val preview = ItemStackSerializer.deserialize(sellItemB64) ?: ItemStack(Material.BARRIER)
        pane.addItem(GuiItem(preview) { event ->
            event.isCancelled = true
            val hand = player.inventory.itemInMainHand
            if (hand.type != Material.AIR && hand.amount > 0) {
                sellItemB64 = ItemStackSerializer.serialize(hand.clone().apply { amount = 1 })
                sellAmount = hand.amount.coerceAtLeast(1)
                render(player)
            }
        }, 1, 1)

        // Sell amount controls.
        pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.edit.sell_up", "amount" to sellAmount))) {
            it.isCancelled = true; sellAmount += 1; render(player)
        }, 2, 0)
        pane.addItem(GuiItem(decorated(Material.PAPER, lang.msg("gui.shop.edit.sell_amount", "amount" to sellAmount))), 2, 1)
        pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.edit.sell_down", "amount" to sellAmount))) {
            it.isCancelled = true; sellAmount = (sellAmount - 1).coerceAtLeast(1); render(player)
        }, 2, 2)

        // Cost column: money controls for SELL/BUY, item-from-hand for TRADE.
        if (shop.direction == SignDirection.TRADE) {
            val costPreview = ItemStackSerializer.deserialize(costItemB64) ?: ItemStack(Material.BARRIER)
            pane.addItem(GuiItem(costPreview) { event ->
                event.isCancelled = true
                val hand = player.inventory.itemInMainHand
                if (hand.type != Material.AIR && hand.amount > 0) {
                    costItemB64 = ItemStackSerializer.serialize(hand.clone().apply { amount = 1 })
                    render(player)
                }
            }, 4, 0)
            pane.addItem(GuiItem(decorated(Material.PAPER, lang.msg("gui.shop.edit.cost_item"))), 4, 1)
            pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.edit.cost_amount_up", "amount" to costAmount))) {
                it.isCancelled = true; costAmount += 1; render(player)
            }, 4, 2)
            pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.edit.cost_amount_down", "amount" to costAmount))) {
                it.isCancelled = true; costAmount = (costAmount - 1).coerceAtLeast(1); render(player)
            }, 5, 2)
        } else {
            pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.edit.cost_up", "cost" to costAmount))) {
                it.isCancelled = true; costAmount += 10; render(player)
            }, 4, 0)
            pane.addItem(GuiItem(MenuItems.currencyIcon(lang.msg("gui.shop.edit.cost", "cost" to costAmount))), 4, 1)
            pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.edit.cost_down", "cost" to costAmount))) {
                it.isCancelled = true; costAmount = (costAmount - 10).coerceAtLeast(1); render(player)
            }, 4, 2)
        }

        // Hopper toggles + freeze.
        pane.addItem(GuiItem(decorated(if (hopperIn) Material.HOPPER else Material.GRAY_DYE, lang.msg("gui.shop.edit.hopper_in", "state" to hopperIn))) {
            it.isCancelled = true; hopperIn = !hopperIn; render(player)
        }, 6, 0)
        pane.addItem(GuiItem(decorated(if (hopperOut) Material.HOPPER else Material.GRAY_DYE, lang.msg("gui.shop.edit.hopper_out", "state" to hopperOut))) {
            it.isCancelled = true; hopperOut = !hopperOut; render(player)
        }, 6, 1)
        pane.addItem(GuiItem(decorated(if (frozen) Material.BLUE_ICE else Material.WATER_BUCKET, lang.msg("gui.shop.edit.freeze", "state" to frozen))) {
            it.isCancelled = true; frozen = !frozen; render(player)
        }, 6, 2)

        // Search toggle.
        pane.addItem(GuiItem(decorated(if (searchEnabled) Material.SPYGLASS else Material.GRAY_DYE, lang.msg("gui.shop.edit.search", "state" to searchEnabled))) {
            it.isCancelled = true; searchEnabled = !searchEnabled; render(player)
        }, 7, 1)

        // Save + delete.
        pane.addItem(GuiItem(decorated(Material.LIME_STAINED_GLASS_PANE, lang.msg("gui.shop.edit.save"))) {
            it.isCancelled = true
            shopRepository.upsert(applyEdits(shop, sellItemB64, sellAmount, costAmount, hopperIn, hopperOut, frozen, searchEnabled, costItemB64))
            player.closeInventory()
            player.sendMessage(lang.msg("shop.edit.saved"))
        }, 8, 0)
        pane.addItem(GuiItem(decorated(Material.RED_CONCRETE, lang.msg("gui.shop.edit.delete"))) {
            it.isCancelled = true
            management.delete(shop.owner, shop.id)
            player.closeInventory()
            player.sendMessage(lang.msg("shop.delete.done"))
        }, 8, 2)

        gui.addPane(pane)
        gui.blockItemTheft()
        gui.show(player)
    }

    private fun decorated(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name.decoration(TextDecoration.ITALIC, false))
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    @Suppress("LongParameterList")
    companion object {
        /** Pure: produce the edited Shop copy. Amounts clamp to >= 1 (Shop.init requires it). */
        fun applyEdits(
            shop: Shop, sellItemB64: String, sellAmount: Int, costAmount: Int,
            hopperIn: Boolean, hopperOut: Boolean, frozen: Boolean,
            searchEnabled: Boolean, costItemB64: String = shop.costItem,
        ): Shop = shop.copy(
            sellItem = sellItemB64,
            sellAmount = sellAmount.coerceAtLeast(1),
            costItem = costItemB64,
            costAmount = costAmount.coerceAtLeast(1),
            hopperAllowIn = hopperIn,
            hopperAllowOut = hopperOut,
            frozen = frozen,
            searchEnabled = searchEnabled,
        )
    }
}