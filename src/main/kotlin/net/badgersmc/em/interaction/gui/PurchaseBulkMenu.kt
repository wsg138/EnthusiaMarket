package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ContainerTradeService
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.interaction.Menu
import net.badgersmc.em.interaction.blockItemTheft
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Quantity picker for money-based shop trades. It never executes a trade directly. */
class PurchaseBulkMenu(
    private val shop: Shop,
    private val tradeService: ContainerTradeService,
    private val lang: LangService,
    private val selected: Int = 1,
) : Menu {

    override fun open(player: Player) {
        val summary = PurchaseMenu.summary(shop, player, tradeService)
        val gui = ChestGui(3, ComponentHolder.of(lang.msg("gui.shop.bulk_title")))
        val pane = StaticPane(9, 3)
        pane.addItem(GuiItem(named(Material.PAPER, lang.msg("gui.shop.bulk_status"), listOf(
            lang.msg("gui.shop.bulk_stock", "trades" to summary.stockText),
            lang.msg("gui.shop.bulk_affordable", "trades" to summary.affordable),
            lang.msg("gui.shop.bulk_receive", "amount" to summary.receivedFor(summary.maxTrades)),
        ))) { it.isCancelled = true }, 4, 0)

        listOf(1, 8, 16, 32, 64).forEachIndexed { index, amount ->
            quantityButton(pane, player, amount, index + 1, 1, summary)
        }
        quantityButton(pane, player, summary.maxTrades, 7, 1, summary, "gui.shop.bulk_max")
        pane.addItem(GuiItem(named(Material.CYAN_CONCRETE, lang.msg("gui.shop.bulk_custom"), listOf(
            lang.msg("gui.shop.bulk_custom_lore"),
        ))) {
            it.isCancelled = true
            waiting[player.uniqueId] = Pending(shop, tradeService, lang)
            player.closeInventory()
            player.sendMessage(lang.msg("gui.shop.bulk_prompt"))
        }, 4, 1)
        pane.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.shop.bulk_back"))) {
            it.isCancelled = true
            PurchaseMenu(shop, tradeService, lang, selected).open(player)
        }, 3, 2)
        pane.addItem(GuiItem(named(Material.BARRIER, lang.msg("gui.shop.close"))) {
            it.isCancelled = true
            player.closeInventory()
        }, 5, 2)

        gui.addPane(pane)
        gui.blockItemTheft()
        gui.show(player)
    }

    private fun quantityButton(
        pane: StaticPane, player: Player, amount: Int, x: Int, y: Int,
        summary: PurchaseMenu.Companion.Summary, labelKey: String = "gui.shop.bulk_quantity",
    ) {
        val allowed = amount in 1..summary.maxTrades
        val material = if (allowed) Material.LIME_CONCRETE else Material.RED_CONCRETE
        pane.addItem(GuiItem(named(material, lang.msg(labelKey, "trades" to amount), listOf(
            lang.msg("gui.shop.bulk_pay", "amount" to summary.paymentFor(amount)),
            lang.msg("gui.shop.bulk_receive", "amount" to summary.receivedFor(amount)),
        ))) {
            it.isCancelled = true
            if (allowed) PurchaseMenu(shop, tradeService, lang, amount).open(player)
        }, x, y)
    }

    private fun named(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack =
        ItemStack(material).also { item -> item.itemMeta = item.itemMeta?.also { meta ->
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        } }

    private data class Pending(val shop: Shop, val tradeService: ContainerTradeService, val lang: LangService)

    companion object {
        private const val MAX_CUSTOM_TRADES = 10_000
        private val waiting = ConcurrentHashMap<UUID, Pending>()

        fun isWaiting(playerId: UUID): Boolean = waiting.containsKey(playerId)

        fun handleChat(player: Player, message: String, fallbackLang: LangService) {
            val pending = waiting.remove(player.uniqueId) ?: return
            if (message.equals("cancel", ignoreCase = true)) {
                player.sendMessage(fallbackLang.msg("gui.shop.bulk_cancelled"))
                return
            }
            val amount = message.toIntOrNull()
            if (amount == null || amount !in 1..MAX_CUSTOM_TRADES) {
                player.sendMessage(fallbackLang.msg("gui.shop.bulk_invalid"))
                waiting[player.uniqueId] = pending
                return
            }
            PurchaseMenu(pending.shop, pending.tradeService, pending.lang, amount).open(player)
        }
    }
}
