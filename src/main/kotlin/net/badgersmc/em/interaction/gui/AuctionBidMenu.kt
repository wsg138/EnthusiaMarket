package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.AuctionLifecycleService
import net.badgersmc.em.application.AuctionResult
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.interaction.Menu
import net.badgersmc.em.interaction.blockItemTheft
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

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
            GuiItem(decorated(Material.EMERALD, lang.msg("gui.auction_bid.summary", "stall" to auction.stallId.value),
                listOf(lang.msg("gui.auction_bid.current", "amount" to (auction.highBid?.amount ?: auction.startingBid)),
                    lang.msg("gui.auction_bid.auction_id", "id" to auction.id.value)))) { it.isCancelled = true },
            4, 0)

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

        val message = when (val result = auctionService.placeBid(auction.id, player.uniqueId, amount)) {
            is AuctionResult.Success -> lang.msg(
                "admin.bid.success",
                "amount" to (result.auction.highBid?.amount ?: amount),
                "id" to result.auction.id,
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
        meta.displayName(name)
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    private companion object {
        const val BID_PERMISSION = "enthusiamarket.auction.bid"
    }
}
