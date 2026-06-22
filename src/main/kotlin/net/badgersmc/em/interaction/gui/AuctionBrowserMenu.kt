package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.blockItemTheft
import net.badgersmc.nexus.paper.gui.LivePollingMenu
import net.badgersmc.nexus.paper.gui.itemStack
import net.badgersmc.nexus.scheduler.NexusScheduler
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Live-updating browser of every open auction (REQ-029).
 *
 * Subclasses [LivePollingMenu] so the framework handles the two-tick
 * pattern (async prefetch + main-thread render) and `setOnClose` cancels
 * both tasks. This class only contributes:
 *
 * - [prefetch]: read `auctions.allOpen()`, resolve each stall + bidder
 *   name via [OfflinePlayerNameCache], publish into [latest]. Off main
 *   thread.
 * - [render]: read the latest snapshot, paint the chest GUI. Main thread,
 *   no I/O.
 *
 * The sort mode is a `@Volatile` flag flipped by the controls row; the
 * next render call picks it up.
 */
class AuctionBrowserMenu(
    private val auctions: AuctionRepository,
    private val stalls: StallRepository,
    scheduler: NexusScheduler,
    private val lang: LangService,
    private val nameCache: OfflinePlayerNameCache = OfflinePlayerNameCache()
) : LivePollingMenu(scheduler, rows = ROWS, refreshTicks = REFRESH_TICKS) {

    enum class SortMode(val labelKey: String) {
        HIGHEST_BID("gui.auctions.sort_highest_bid"),
        LOWEST_BID("gui.auctions.sort_lowest_bid"),
        ENDING_SOON("gui.auctions.sort_ending_soon"),
        ENDING_LATEST("gui.auctions.sort_ending_latest");

        fun next(): SortMode = entries[(ordinal + 1) % entries.size]
    }

    /**
     * Pre-rendered data for one auction entry. Built off the main thread so
     * the main-thread repaint doesn't touch JDBC, WorldGuard, or
     * `OfflinePlayer.name` calls.
     */
    private data class EntryView(
        val auction: Auction,
        val stallWorld: String?,
        val stallRegion: String?,
        val bidderName: String?
    )

    private data class Snapshot(val entries: List<EntryView>) {
        companion object {
            val EMPTY = Snapshot(emptyList())
        }
    }

    private companion object {
        const val ROWS = 6
        const val ITEMS_PER_PAGE = 45 // rows 0..4
        const val REFRESH_TICKS = 20L

        // lang.msg() map key constants — extracted to avoid StringLiteralDuplication
        private const val KEY_AMOUNT = "amount"
        private const val KEY_STALL = "stall"
        private const val KEY_ID = "id"
    }

    @Volatile
    private var sortMode: SortMode = SortMode.HIGHEST_BID

    @Volatile
    private var currentPage: Int = 0

    private val latest: AtomicReference<Snapshot> = AtomicReference(Snapshot.EMPTY)

    override fun title(): Component = lang.msg("gui.auctions.title")

    /**
     * Off-main-thread snapshot builder. Reads every open auction, resolves
     * the stall + high-bidder name for each, and atomically swaps the
     * result into [latest] for the main-thread renderer.
     */
    override fun prefetch() {
        val open = auctions.allOpen()
        val stallCache: MutableMap<String, Stall?> = HashMap()
        val entries = open.map { auction ->
            val stall = stallCache.getOrPut(auction.stallId.value) {
                stalls.findById(auction.stallId)
            }
            val bidderName = auction.highBid?.let { nameCache.resolveOffMainThread(it.bidder) }
            EntryView(
                auction = auction,
                stallWorld = stall?.world,
                stallRegion = stall?.regionId,
                bidderName = bidderName
            )
        }
        latest.set(Snapshot(entries))
    }

    override fun render(gui: ChestGui) {
        // Anti-dupe: cancel raw item movement (collect-to-cursor / drag). The framework base
        // creates this gui, so we harden it here; re-applying each refresh is idempotent.
        gui.blockItemTheft()
        val snapshot = latest.get()
        val now = Instant.now()
        val sorted = snapshot.entries.sortedWith(entryComparator(sortMode))
        val pageCount = ((sorted.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)
        if (currentPage >= pageCount) currentPage = pageCount - 1
        if (currentPage < 0) currentPage = 0

        gui.panes.clear()

        val itemsPane = PaginatedPane(0, 0, 9, 5)
        for (pageIdx in 0 until pageCount) {
            val pagePane = OutlinePane(0, 0, 9, 5, Pane.Priority.LOWEST)
            val slice = sorted.drop(pageIdx * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE)
            for (entry in slice) {
                pagePane.addItem(GuiItem(entryIcon(entry, now)) { it.isCancelled = true })
            }
            itemsPane.addPane(pageIdx, pagePane)
        }
        itemsPane.page = currentPage
        gui.addPane(itemsPane)

        gui.addPane(buildControls(gui, pageCount, sorted.size))
    }

    private fun buildControls(gui: ChestGui, pageCount: Int, total: Int): StaticPane {
        val pane = StaticPane(0, 5, 9, 1)

        pane.addItem(GuiItem(itemStack(Material.ARROW) {
            name(lang.msg("gui.auctions.prev"))
        }) {
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
        pane.addItem(GuiItem(itemStack(Material.HOPPER) {
            name(sortName)
            lore(lang.msg("gui.auctions.sort_lore_click"))
        }) {
            it.isCancelled = true
            sortMode = sortMode.next()
            currentPage = 0
            render(gui); gui.update()
        }, 2, 0)

        pane.addItem(GuiItem(itemStack(Material.PAPER) {
            name(lang.msg("gui.auctions.page_indicator", "current" to (currentPage + 1), "total" to pageCount))
            lore(lang.msg("gui.auctions.page_lore_count", "count" to total))
        }) { it.isCancelled = true }, 4, 0)

        pane.addItem(GuiItem(itemStack(Material.ARROW) {
            name(lang.msg("gui.auctions.next"))
        }) {
            it.isCancelled = true
            if (currentPage < pageCount - 1) {
                currentPage++
                render(gui); gui.update()
            }
        }, 6, 0)

        pane.addItem(GuiItem(itemStack(Material.BARRIER) {
            name(lang.msg("gui.auctions.close"))
        }) {
            it.isCancelled = true
            (it.whoClicked as? Player)?.closeInventory()
        }, 8, 0)

        return pane
    }

    private fun entryComparator(mode: SortMode): Comparator<EntryView> = when (mode) {
        SortMode.HIGHEST_BID ->
            compareByDescending { it.auction.highBid?.amount ?: it.auction.startingBid }
        SortMode.LOWEST_BID ->
            compareBy { it.auction.highBid?.amount ?: it.auction.startingBid }
        SortMode.ENDING_SOON ->
            compareBy { it.auction.endAt }
        SortMode.ENDING_LATEST ->
            compareByDescending { it.auction.endAt }
    }

    private fun entryIcon(entry: EntryView, now: Instant): ItemStack {
        val auction = entry.auction
        val currentBid = auction.highBid?.amount ?: auction.startingBid
        val remaining = Duration.between(now, auction.endAt)

        val bidLine: Component = if (auction.highBid != null) {
            val bidder = entry.bidderName ?: lang.raw("common.unknown_player")
            lang.msg(
                "gui.auctions.entry_lore_current_with_bidder",
                KEY_AMOUNT to currentBid,
                "bidder" to bidder
            )
        } else {
            lang.msg("gui.auctions.entry_lore_current_no_bids", KEY_AMOUNT to currentBid)
        }

        return itemStack(Material.EMERALD) {
            name(lang.msg("gui.auctions.entry_name", KEY_STALL to auction.stallId.value))
            lore(
                lang.msg(
                    "gui.auctions.entry_lore_region",
                    "world" to (entry.stallWorld ?: "?"),
                    "region" to (entry.stallRegion ?: "?")
                ),
                bidLine,
                lang.msg("gui.auctions.entry_lore_starting", KEY_AMOUNT to auction.startingBid),
                lang.msg("gui.auctions.entry_lore_time_left", "time" to formatRemaining(remaining)),
                lang.msg("gui.auctions.entry_lore_id", KEY_ID to auction.id.value)
            )
        }
    }

    private fun formatRemaining(d: Duration): String {
        if (d.isNegative || d.isZero) return lang.raw("gui.auctions.time_ended")
        val days = d.toDays()
        val hours = d.toHours() % 24
        val minutes = d.toMinutes() % 60
        val seconds = d.seconds % 60
        val parts = mutableListOf<String>()
        if (days > 0) parts.add("${days}d")
        if (hours > 0) parts.add("${hours}h")
        if (minutes > 0) parts.add("${minutes}m")
        parts.add("${seconds}s")
        return parts.joinToString(" ")
    }
}
