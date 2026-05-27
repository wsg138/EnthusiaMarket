package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.time.Instant

/**
 * Live-updating browser of every open auction (REQ-029).
 *
 * Layout: 6-row chest. Rows 0..4 (45 slots) display auctions via [PaginatedPane].
 * Bottom row holds: prev page | sort toggle | page indicator | next page | close.
 *
 * A 20-tick scheduler task re-renders the contents every second while the menu remains
 * open. The task self-cancels when the player closes the GUI or logs off.
 */
class AuctionBrowserMenu(
    private val auctions: AuctionRepository,
    private val stalls: StallRepository,
    private val plugin: JavaPlugin,
    private val lang: LangService
) : Menu {

    enum class SortMode(val labelKey: String) {
        HIGHEST_BID("gui.auctions.sort_highest_bid"),
        LOWEST_BID("gui.auctions.sort_lowest_bid"),
        ENDING_SOON("gui.auctions.sort_ending_soon"),
        ENDING_LATEST("gui.auctions.sort_ending_latest");

        fun next(): SortMode = entries[(ordinal + 1) % entries.size]
    }

    private companion object {
        const val ROWS = 6
        const val ITEMS_PER_PAGE = 45 // rows 0..4
        const val REFRESH_TICKS = 20L
    }

    private var sortMode: SortMode = SortMode.HIGHEST_BID
    private var currentPage: Int = 0

    override fun open(player: Player) {
        val gui = ChestGui(ROWS, ComponentHolder.of(lang.msg("gui.auctions.title")))
        gui.setOnTopClick { it.isCancelled = true }
        gui.setOnBottomClick { it.isCancelled = true }

        render(gui)
        gui.show(player)

        val task: BukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (player.openInventory.topInventory != gui.inventory) return@Runnable
            render(gui)
            gui.update()
        }, REFRESH_TICKS, REFRESH_TICKS)

        gui.setOnClose { task.cancel() }
    }

    private fun render(gui: ChestGui) {
        val now = Instant.now()
        val open = auctions.allOpen().sortedWith(comparatorFor(sortMode))
        val pageCount = ((open.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)
        if (currentPage >= pageCount) currentPage = pageCount - 1
        if (currentPage < 0) currentPage = 0

        gui.panes.clear()

        val itemsPane = PaginatedPane(0, 0, 9, 5)
        for (pageIdx in 0 until pageCount) {
            val pagePane = OutlinePane(0, 0, 9, 5, Pane.Priority.LOWEST)
            val slice = open.drop(pageIdx * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE)
            for (auction in slice) {
                pagePane.addItem(GuiItem(auctionIcon(auction, now)) { it.isCancelled = true })
            }
            itemsPane.addPane(pageIdx, pagePane)
        }
        itemsPane.page = currentPage
        gui.addPane(itemsPane)

        gui.addPane(buildControls(gui, pageCount, open.size))
    }

    private fun buildControls(gui: ChestGui, pageCount: Int, total: Int): StaticPane {
        val pane = StaticPane(0, 5, 9, 1)

        pane.addItem(GuiItem(decorated(Material.ARROW, lang.msg("gui.auctions.prev"))) {
            it.isCancelled = true
            if (currentPage > 0) {
                currentPage--
                render(gui); gui.update()
            }
        }, 0, 0)

        val sortName = lang.msg(
            "gui.auctions.sort_name",
            "mode" to lang.raw(sortMode.labelKey)
        )
        pane.addItem(GuiItem(decorated(
            Material.HOPPER,
            sortName,
            listOf(lang.msg("gui.auctions.sort_lore_click"))
        )) {
            it.isCancelled = true
            sortMode = sortMode.next()
            currentPage = 0
            render(gui); gui.update()
        }, 2, 0)

        pane.addItem(GuiItem(decorated(
            Material.PAPER,
            lang.msg("gui.auctions.page_indicator", "current" to (currentPage + 1), "total" to pageCount),
            listOf(lang.msg("gui.auctions.page_lore_count", "count" to total))
        )) { it.isCancelled = true }, 4, 0)

        pane.addItem(GuiItem(decorated(Material.ARROW, lang.msg("gui.auctions.next"))) {
            it.isCancelled = true
            if (currentPage < pageCount - 1) {
                currentPage++
                render(gui); gui.update()
            }
        }, 6, 0)

        pane.addItem(GuiItem(decorated(Material.BARRIER, lang.msg("gui.auctions.close"))) {
            it.isCancelled = true
            (it.whoClicked as? Player)?.closeInventory()
        }, 8, 0)

        return pane
    }

    private fun decorated(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name)
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    private fun comparatorFor(mode: SortMode): Comparator<Auction> = when (mode) {
        SortMode.HIGHEST_BID -> compareByDescending { it.highBid?.amount ?: it.startingBid }
        SortMode.LOWEST_BID -> compareBy { it.highBid?.amount ?: it.startingBid }
        SortMode.ENDING_SOON -> compareBy { it.endAt }
        SortMode.ENDING_LATEST -> compareByDescending { it.endAt }
    }

    private fun auctionIcon(auction: Auction, now: Instant): ItemStack {
        val stall = stalls.findById(auction.stallId)
        val currentBid = auction.highBid?.amount ?: auction.startingBid
        val remaining = Duration.between(now, auction.endAt)

        val bidLine: Component = if (auction.highBid != null) {
            val bidderName = Bukkit.getOfflinePlayer(auction.highBid!!.bidder).name ?: "?"
            lang.msg(
                "gui.auctions.entry_lore_current_with_bidder",
                "amount" to currentBid,
                "bidder" to bidderName
            )
        } else {
            lang.msg("gui.auctions.entry_lore_current_no_bids", "amount" to currentBid)
        }

        return decorated(
            Material.EMERALD,
            lang.msg("gui.auctions.entry_name", "stall" to auction.stallId.value),
            listOf(
                lang.msg(
                    "gui.auctions.entry_lore_region",
                    "world" to (stall?.world ?: "?"),
                    "region" to (stall?.regionId ?: "?")
                ),
                bidLine,
                lang.msg("gui.auctions.entry_lore_starting", "amount" to auction.startingBid),
                lang.msg("gui.auctions.entry_lore_time_left", "time" to formatRemaining(remaining)),
                lang.msg("gui.auctions.entry_lore_id", "id" to auction.id.value)
            )
        )
    }

    private fun formatRemaining(d: Duration): String {
        if (d.isNegative || d.isZero) return lang.raw("gui.auctions.time_ended")
        val days = d.toDays()
        val hours = d.toHours() % 24
        val minutes = d.toMinutes() % 60
        val seconds = d.seconds % 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (days > 0 || hours > 0) append("${hours}h ")
            if (days > 0 || hours > 0 || minutes > 0) append("${minutes}m ")
            append("${seconds}s")
        }
    }
}
