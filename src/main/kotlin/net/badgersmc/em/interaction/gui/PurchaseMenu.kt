package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ContainerTradeResult
import net.badgersmc.em.application.ContainerTradeService
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ItemStackMatch
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.badgersmc.em.interaction.MenuItems
import net.badgersmc.em.interaction.blockItemTheft
import net.badgersmc.em.interaction.blockTopInventoryExcept
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta

/**
 * Trade GUI for a sign shop. Direction-aware (REQ-006):
 *
 * Uses "YOU RECEIVE" / "YOU GIVE" labels to remove BUY/SELL confusion.
 *
 * - **SELL** sign: YOU RECEIVE the shop's item, YOU GIVE currency.
 * - **BUY** sign:  YOU RECEIVE currency, YOU GIVE the shop's item.
 * - **TRADE** sign: YOU RECEIVE the shop's item, YOU GIVE a specific item.
 */
class PurchaseMenu(
    private val shop: Shop,
    private val tradeService: ContainerTradeService,
    private val lang: LangService,
    initialMultiplier: Int = 1,
) : Menu {

    private val sellStack: ItemStack? by lazy { ItemStackSerializer.deserialize(shop.sellItem) }
    private val sellName: String by lazy { sellStack?.type?.name?.lowercase()?.replace('_', ' ') ?: "?" }
    private val ownerName: String by lazy { Bukkit.getOfflinePlayer(shop.owner).name ?: "Unknown" }

    override fun open(player: Player) {
        dirLabel = ShopDisplay.directionLabel(shop.direction)

        // Chat direction reminder
        val chatHintKey = when (shop.direction) {
            SignDirection.SELL -> "gui.shop.chat_reminder_buy"
            SignDirection.BUY -> "gui.shop.chat_reminder_sell"
            SignDirection.TRADE -> "gui.shop.chat_reminder_trade"
        }
        player.sendMessage(lang.msg(chatHintKey, "owner" to ownerName, "item" to sellName, "amount" to shop.sellAmount))

        render(player)
    }

    private var multiplier = initialMultiplier.coerceAtLeast(1)
    private lateinit var dirLabel: String
    private var replacingInventory = false
    private var placementReturned = false
    private var hasRendered = false

    private fun render(player: Player) {
        val gui = ChestGui(5, ComponentHolder.of(lang.msg("gui.shop.title", "amount" to (shop.sellAmount * multiplier))))
        val pane = StaticPane(9, 5)

        // --- Row 0: YOU RECEIVE / arrow / YOU GIVE labels ---
        val receiveLabel = lang.msg("gui.shop.receive_label")
        val giveLabel = lang.msg("gui.shop.give_label")
        pane.addItem(GuiItem(decorated(Material.GREEN_STAINED_GLASS_PANE, receiveLabel)), 2, 0)
        pane.addItem(GuiItem(decorated(Material.ARROW, Component.text("→"))), 4, 0)
        if (shop.direction == SignDirection.TRADE) {
            pane.addItem(GuiItem(decorated(Material.RED_STAINED_GLASS_PANE, giveLabel, listOf(
                lang.msg("gui.shop.trade_drop_hint")
            ))), 6, 0)
        } else {
            pane.addItem(GuiItem(decorated(Material.RED_STAINED_GLASS_PANE, giveLabel)), 6, 0)
        }

        // --- Row 1: the actual items ---
        val row = buildRowItems()
        pane.addItem(GuiItem(decorated(row.receiveItem, row.receiveName, row.receiveLore)), 2, 1)
        pane.addItem(GuiItem(statusItem(PurchaseMenu.summary(shop, player, tradeService))) {
            it.isCancelled = true
        }, 4, 1)
        if (shop.direction == SignDirection.TRADE) {
            // Leave slot 15 empty — GuiItem blocks native item placement.
            // Visual border indicates the drop zone (LumaGuilds GuildBannerMenu pattern).
            addPlacementSlotBorder(pane)
        } else {
            pane.addItem(GuiItem(decorated(row.giveItem, row.giveName, row.giveLore)), 6, 1)
        }

        // --- Row 2/3: confirm the selected amount, or select a bulk amount ---
        buildConfirmButton(pane, player)
        pane.addItem(GuiItem(decorated(Material.CHEST, lang.msg("gui.shop.bulk_button"), listOf(
            lang.msg("gui.shop.bulk_button_lore", "trades" to multiplier),
        ))) {
            it.isCancelled = true
            PurchaseBulkMenu(shop, tradeService, lang, multiplier).open(player)
        }, 4, 3)

        // Shulker box preview button (IS2-12, REQ-298)
        addShulkerPreview(pane, player)

        gui.addPane(pane)
        if (shop.direction == SignDirection.TRADE) {
            gui.blockTopInventoryExcept(15) // slot 15 = cost placement slot
            gui.setOnClose { event ->
                if (!replacingInventory) returnPlacementItem(event.player as Player, event.inventory.getItem(15))
            }
        } else {
            gui.blockItemTheft()
        }
        // Save the placement slot item before the old inventory is destroyed,
        // then restore it once the new inventory is visible (CR: render wipes slot 15).
        val savedSlot15 = if (shop.direction == SignDirection.TRADE && hasRendered)
            player.openInventory?.topInventory?.getItem(15)?.clone() else null
        replacingInventory = savedSlot15 != null
        gui.show(player)
        hasRendered = true
        replacingInventory = false
        if (savedSlot15 != null) {
            player.openInventory.topInventory.setItem(15, savedSlot15)
        }
    }

    internal fun returnPlacementItem(player: Player, item: ItemStack?) {
        if (placementReturned || item == null || item.type.isAir) return
        placementReturned = true
        val remainder = player.inventory.addItem(item.clone())
        remainder.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun buildConfirmButton(pane: StaticPane, player: Player) {
        val buttonKey = when (shop.direction) {
            SignDirection.SELL -> "gui.shop.confirm_buy"
            SignDirection.BUY -> "gui.shop.confirm_sell"
            SignDirection.TRADE -> "gui.shop.confirm_trade"
        }
        val canPurchase = multiplier <= PurchaseMenu.summary(shop, player, tradeService).maxTrades
        val buttonLore = listOf(
            lang.msg("gui.shop.confirm_lore", "dir" to dirLabel, "direction" to dirLabel),
            lang.msg(if (canPurchase) "gui.shop.confirm_ready" else "gui.shop.confirm_unaffordable"),
        )

        pane.addItem(GuiItem(decorated(
            if (canPurchase) Material.LIME_CONCRETE else Material.RED_CONCRETE,
            lang.msg(buttonKey, "trades" to multiplier), buttonLore,
        )) { event ->
            event.isCancelled = true
            if (!canPurchase) return@GuiItem
            executeTrade(player)
            multiplier = 1
            render(player)
        }, 4, 2)
    }

    private fun executeTrade(player: Player) {
        if (shop.direction == SignDirection.TRADE) {
            executeTradeFromSlot(player)
            return
        }
        // Clamp multiplier to actual available trades (stock may have changed
        // since the GUI rendered). Prevents the confusing "bought N, rest failed"
        // experience when denormalized stockCount is stale.
        val available = if (shop.direction == SignDirection.BUY) multiplier else ShopDisplay.tradesAvailable(shop)
        if (available <= 0) {
            player.sendMessage(lang.msg("shop.trade.failure", "reason" to "Out of stock"))
            return
        }
        val effectiveMultiplier = multiplier.coerceAtMost(available)
        if (effectiveMultiplier < multiplier) {
            player.sendMessage(lang.msg("shop.trade.multiplier_capped",
                "asked" to multiplier, "available" to effectiveMultiplier))
        }
        var lastResult: ContainerTradeResult = ContainerTradeResult.Success("")
        var completed = 0
        var remaining = effectiveMultiplier
        while (remaining > 0) {
            val result = when (shop.direction) {
                SignDirection.SELL -> tradeService.executeSell(shop, player.uniqueId)
                SignDirection.BUY -> tradeService.executeBuy(shop, player.uniqueId)
                else -> break
            }
            lastResult = result
            if (result is ContainerTradeResult.Success) {
                completed++
                remaining--
            } else {
                break
            }
        }
        reportTradeResult(player, completed, effectiveMultiplier, lastResult)
    }

    private fun executeTradeFromSlot(player: Player) {
        val slotItem = readAndValidateSlot15(player) ?: return
        val needed = shop.costAmount * multiplier
        if (slotItem.amount < needed) {
            player.sendMessage(
                lang.msg("shop.trade.failure", "reason" to "Need $needed, only ${slotItem.amount} placed"),
            )
            return
        }
        val result = tradeService.executeTradeWithItem(shop, player.uniqueId, slotItem, multiplier)
        if (result is ContainerTradeResult.Success) {
            updateSlot15AfterTrade(player, slotItem, needed)
            player.sendMessage(lang.msg("shop.trade.success", "message" to result.message))
        } else {
            reportTotalFailure(player, result)
        }
        multiplier = 1
        render(player)
    }

    private fun readAndValidateSlot15(player: Player): ItemStack? {
        val slotItem = player.openInventory.topInventory.getItem(15)
        if (slotItem == null || slotItem.type.isAir) {
            player.sendMessage(lang.msg("shop.trade.failure", "reason" to "Place your trade item in the slot"))
            return null
        }
        val costStack = ItemStackSerializer.deserialize(shop.costItem)
        if (costStack == null || !slotItem.isSimilar(costStack)) {
            player.sendMessage(lang.msg("shop.trade.failure", "reason" to
                "Wrong item — expected ${costStack?.type?.name?.lowercase()?.replace('_', ' ')}"),
            )
            return null
        }
        return slotItem
    }

    private fun updateSlot15AfterTrade(player: Player, slotItem: ItemStack, needed: Int) {
        val topInv = player.openInventory.topInventory
        val remaining = slotItem.amount - needed
        if (remaining > 0) {
            slotItem.amount = remaining
            topInv.setItem(15, slotItem)
        } else {
            topInv.setItem(15, null)
        }
    }

    private fun reportTradeResult(
        player: Player,
        completed: Int,
        total: Int,
        lastResult: ContainerTradeResult,
    ) {
        when {
            completed == total -> player.sendMessage(
                lang.msg("shop.trade.success", "message" to (lastResult as ContainerTradeResult.Success).message),
            )
            completed > 0 -> reportPartial(player, completed, total, lastResult)
            else -> reportTotalFailure(player, lastResult)
        }
    }

    private fun reportPartial(
        player: Player,
        completed: Int,
        total: Int,
        lastResult: ContainerTradeResult,
    ) {
        when (lastResult) {
            is ContainerTradeResult.Failure -> player.sendMessage(
                lang.msg("shop.trade.partial_failure",
                    "completed" to completed, "total" to total, "reason" to lastResult.reason),
            )
            is ContainerTradeResult.CompensationFailed -> {
                player.sendMessage(lang.msg("shop.trade.partial_compensation",
                    "completed" to completed, "total" to total, "error" to lastResult.error))
                player.sendMessage(lang.msg("shop.trade.compensation_note", "compensation" to lastResult.compensation))
            }
            else -> {} // unreachable
        }
    }

    private fun reportTotalFailure(player: Player, lastResult: ContainerTradeResult) {
        when (lastResult) {
            is ContainerTradeResult.Failure -> player.sendMessage(
                lang.msg("shop.trade.failure", "reason" to lastResult.reason),
            )
            is ContainerTradeResult.CompensationFailed -> {
                player.sendMessage(lang.msg("shop.trade.compensation_failed", "error" to lastResult.error))
                player.sendMessage(lang.msg("shop.trade.compensation_note", "compensation" to lastResult.compensation))
            }
            else -> {} // unreachable
        }
    }

    private fun decorated(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name.decoration(TextDecoration.ITALIC, false))
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    private fun decorated(base: ItemStack, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = base.clone()
        val meta = item.itemMeta ?: return item
        meta.displayName(name.decoration(TextDecoration.ITALIC, false))
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    private fun statusItem(summary: Summary): ItemStack = decorated(Material.PAPER,
        lang.msg("gui.shop.purchase_status", "trades" to multiplier), listOf(
            lang.msg("gui.shop.purchase_stock", "trades" to summary.stockText),
            lang.msg("gui.shop.purchase_affordable", "trades" to summary.affordable),
            lang.msg("gui.shop.purchase_receive", "amount" to summary.receivedFor(multiplier)),
            lang.msg("gui.shop.purchase_pay", "amount" to summary.paymentFor(multiplier)),
        ))

    /** IS2-12, REQ-298: add a shulker preview button when the shop sells a shulker box. */
    private fun addShulkerPreview(pane: StaticPane, player: Player) {
        val sell = sellStack ?: return
        if ((sell.itemMeta as? BlockStateMeta)?.blockState !is org.bukkit.block.ShulkerBox) return
        pane.addItem(GuiItem(decorated(
            Material.SHULKER_BOX,
            lang.msg("gui.shulker_preview.name"),
            listOf(lang.msg("gui.shulker_preview.lore")),
        )) { event ->
            event.isCancelled = true
            // Deep-copy through bytes: ItemStack.clone() drops BlockStateMeta NBT (shulker contents)
            val deepCopy = ItemStackSerializer.deserialize(ItemStackSerializer.serialize(sell)) ?: sell
            ShulkerPreviewMenu(deepCopy, lang).open(player)
        }, 8, 0)
    }

    /** Visual border around the empty placement slot 15 for TRADE shops. */
    private fun addPlacementSlotBorder(pane: StaticPane) {
        val borderItem = ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
            .apply { itemMeta = itemMeta?.apply { displayName(Component.text(" ")) } ?: return }
        // Left, right, and bottom border around slot 15 (position 6,1).
        // Row 0 col 6 already holds the YOU GIVE label framing the top.
        listOf(Pair(5, 1), Pair(7, 1), Pair(6, 2)).forEach { (x, y) ->
            pane.addItem(GuiItem(borderItem.clone()), x, y)
        }
    }

    private data class RowItems(
        val receiveItem: ItemStack,
        val receiveName: Component,
        val receiveLore: List<Component>,
        val giveItem: ItemStack,
        val giveName: Component,
        val giveLore: List<Component>,
    )

    @Suppress("CyclomaticComplexMethod")
    private fun buildRowItems(): RowItems {
        val totalAmount = shop.sellAmount * multiplier
        val totalCost = shop.costAmount * multiplier
        val avail = ShopDisplay.tradesAvailable(shop)
        val stockStr = if (avail == Int.MAX_VALUE) "Unlimited" else avail.toString()
        return when (shop.direction) {
            SignDirection.SELL -> RowItems(
                receiveItem = displayAmount(sellStack?.clone() ?: ItemStack(Material.BARRIER), totalAmount),
                receiveName = lang.msg("gui.shop.receive_sell", "amount" to totalAmount, "item" to sellName),
                receiveLore = listOf(
                    lang.msg("gui.shop.sell_lore_stock", "stock" to stockStr),
                    lang.msg("gui.shop.sell_lore_owner", "owner" to ownerName),
                ),
                giveItem = displayAmount(MenuItems.currencyIcon(Component.empty()), totalCost),
                giveName = lang.msg("gui.shop.give_currency", "cost" to totalCost),
                giveLore = listOf(lang.msg("gui.shop.give_currency_lore", "cost" to totalCost)),
            )
            SignDirection.BUY -> RowItems(
                receiveItem = displayAmount(MenuItems.currencyIcon(Component.empty()), totalCost),
                receiveName = lang.msg("gui.shop.receive_currency", "cost" to totalCost),
                receiveLore = listOf(lang.msg("gui.shop.receive_currency_lore", "cost" to totalCost)),
                giveItem = displayAmount(sellStack?.clone() ?: ItemStack(Material.BARRIER), totalAmount),
                giveName = lang.msg("gui.shop.give_item", "amount" to totalAmount, "item" to sellName),
                giveLore = listOf(
                    lang.msg("gui.shop.sell_lore_owner", "owner" to ownerName),
                ),
            )
            SignDirection.TRADE -> RowItems(
                receiveItem = displayAmount(sellStack?.clone() ?: ItemStack(Material.BARRIER), totalAmount),
                receiveName = lang.msg("gui.shop.receive_sell", "amount" to totalAmount, "item" to sellName),
                receiveLore = listOf(
                    lang.msg("gui.shop.sell_lore_stock", "stock" to stockStr),
                ),
                giveItem = ItemStack(Material.AIR), // unused — TRADE renders empty slot instead
                giveName = Component.empty(),
                giveLore = emptyList(),
            )
        }
    }

    private fun displayAmount(item: ItemStack, requested: Int): ItemStack = item.apply {
        amount = requested.coerceIn(1, maxStackSize.coerceAtLeast(1))
    }

    companion object {
        data class Summary(
            val stockTrades: Int,
            val affordable: Int,
            val maxTrades: Int,
            private val receivePerTrade: Int,
            private val paymentPerTrade: Int,
            private val currencyPayment: Boolean,
        ) {
            val stockText: String get() = if (stockTrades == Int.MAX_VALUE) "Unlimited" else stockTrades.toString()
            fun receivedFor(trades: Int): String = (receivePerTrade.toLong() * trades.coerceAtLeast(0)).toString()
            fun paymentFor(trades: Int): String = (paymentPerTrade.toLong() * trades.coerceAtLeast(0)).toString() +
                if (currencyPayment) " currency" else " items"
        }

        fun summary(shop: Shop, player: Player, tradeService: ContainerTradeService): Summary {
            val stock = ShopDisplay.tradesAvailable(shop)
            val sell = ItemStackSerializer.deserialize(shop.sellItem)
            val cost = ItemStackSerializer.deserialize(shop.costItem)
            val affordable = when (shop.direction) {
                SignDirection.SELL -> (tradeService.balanceOf(player.uniqueId) / shop.costAmount)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                SignDirection.BUY -> sell?.let { ItemStackMatch.countSimilar(player.inventory, it) / shop.sellAmount } ?: 0
                SignDirection.TRADE -> cost?.let { ItemStackMatch.countSimilar(player.inventory, it) / shop.costAmount } ?: 0
            }
            return Summary(stock, affordable, minOf(stock, affordable), shop.sellAmount, shop.costAmount,
                shop.direction != SignDirection.TRADE)
        }
    }
}
