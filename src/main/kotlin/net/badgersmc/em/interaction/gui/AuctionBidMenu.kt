package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.AuctionLifecycleService
import net.badgersmc.em.application.AuctionResult
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.interaction.Menu
import net.badgersmc.em.interaction.MenuItems
import net.badgersmc.em.interaction.blockItemTheft
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AuctionBidMenu(
    private val auction: Auction,
    private val auctionService: AuctionLifecycleService,
    private val lang: LangService,
) : Menu {

    @Suppress("LongMethod")
    override fun open(player: Player) {
        val current = auction.highBid?.amount ?: auction.startingBid - 1
        val minimum = current + 1
        val amounts = listOf(minimum, minimum + 10L, minimum + 100L, minimum + 1000L)
        val gui = buildBidGui(player, amounts)
        gui.blockItemTheft()
        gui.show(player)
    }

    private fun buildBidGui(player: Player, amounts: List<Long>): ChestGui {
        val gui = ChestGui(3, ComponentHolder.of(lang.msg("gui.auction_bid.title", "stall" to auction.stallId.value)))
        val pane = StaticPane(9, 3)

        pane.addItem(
            GuiItem(MenuItems.currencyIcon(lang.msg("gui.auction_bid.summary", "stall" to auction.stallId.value),
                listOf(lang.msg("gui.auction_bid.current", "amount" to (auction.highBid?.amount ?: auction.startingBid)))))

            { it.isCancelled = true },
            4, 0)

        // Custom amount button (slot 0)
        pane.addItem(
            GuiItem(decorated(Material.NAME_TAG, lang.msg("gui.auction_bid.custom"))) { event ->
                event.isCancelled = true
                val p = event.whoClicked as? Player ?: return@GuiItem
                p.closeInventory()
                pendingCustomBids[p.uniqueId] = auction to Instant.now()
                p.sendMessage(lang.msg("gui.auction_bid.custom_prompt", "stall" to auction.stallId.value))
            }, 0, 1)

        amounts.forEachIndexed { index, amount ->
            pane.addItem(
                GuiItem(decorated(Material.GOLD_INGOT, lang.msg("gui.auction_bid.amount", "amount" to amount))) { event ->
                    event.isCancelled = true
                    place(player, amount)
                }, 2 + (index * 2), 1)
        }

        pane.addItem(
            GuiItem(decorated(Material.BARRIER, lang.msg("gui.auction_bid.close"))) { event ->
                event.isCancelled = true
                player.closeInventory()
            }, 4, 2)

        gui.addPane(pane)
        return gui
    }

    private fun place(player: Player, amount: Long) {
        if (!player.hasPermission(BID_PERMISSION)) {
            player.sendMessage(lang.msg("gui.auction_bid.no_permission"))
            player.closeInventory()
            return
        }

        val message = when (val result = auctionService.placeBid(auction.id, player.uniqueId, amount,
            player.address.address.hostAddress ?: "unknown")) {
            is AuctionResult.Success -> lang.msg(
                "admin.bid.success",
                "amount" to (result.auction.highBid?.amount ?: amount),
                "stall" to result.auction.stallId.value,
            )
            is AuctionResult.Failure -> lang.msg("admin.bid.failure", "reason" to result.reason)
            is AuctionResult.NotFound -> lang.msg("admin.bid.not_found")
        }
        player.sendMessage(message)
        player.closeInventory()
    }

    private fun decorated(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name.decoration(TextDecoration.ITALIC, false))
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    companion object {
        const val BID_PERMISSION = "enthusiamarket.auction.bid"
        private const val CUSTOM_BID_TIMEOUT_SEC = 30L

        /** Pending custom-bid prompts: player UUID → (auction, when prompted). */
        val pendingCustomBids: ConcurrentHashMap<UUID, Pair<Auction, Instant>> = ConcurrentHashMap()

        /** Handle a chat message that might be a custom bid amount. Returns true if consumed. */
        fun handleChat(player: Player, message: String, lang: LangService, auctionService: AuctionLifecycleService): Boolean {
            val entry = pendingCustomBids.remove(player.uniqueId) ?: return false
            val (auction, promptedAt) = entry
            if (java.time.Duration.between(promptedAt, Instant.now()).seconds > CUSTOM_BID_TIMEOUT_SEC) {
                player.sendMessage(lang.msg("gui.auction_bid.custom_timeout", "stall" to auction.stallId.value))
                return true
            }
            return executeCustomBid(player, message, auction, lang, auctionService)
        }

        private fun executeCustomBid(
            player: Player, message: String, auction: Auction,
            lang: LangService, auctionService: AuctionLifecycleService,
        ): Boolean {
            val amount = message.trim().toLongOrNull()?.takeIf { it > 0 }
            if (amount == null) {
                player.sendMessage(lang.msg("gui.auction_bid.custom_invalid", "input" to message))
                return true
            }
            if (!player.hasPermission(BID_PERMISSION)) {
                player.sendMessage(lang.msg("gui.auction_bid.no_permission"))
                return true
            }
            val result = auctionService.placeBid(auction.id, player.uniqueId, amount,
                player.address.address.hostAddress ?: "unknown")
            player.sendMessage(formatBidResult(result, amount, lang))
            return true
        }

        private fun formatBidResult(result: AuctionResult, amount: Long, lang: LangService) = when (result) {
            is AuctionResult.Success -> lang.msg(
                "admin.bid.success",
                "amount" to (result.auction.highBid?.amount ?: amount),
                "stall" to result.auction.stallId.value,
            )
            is AuctionResult.Failure -> lang.msg("admin.bid.failure", "reason" to result.reason)
            is AuctionResult.NotFound -> lang.msg("admin.bid.not_found")
        }
    }
}
