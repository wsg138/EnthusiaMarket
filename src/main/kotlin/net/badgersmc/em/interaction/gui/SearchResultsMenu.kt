package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.badgersmc.em.interaction.blockItemTheft
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.PriceTicker
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import kotlin.math.abs

/**
 * Paginated /shop search results. The first five rows (45 slots) hold result
 * icons for the requested page; the bottom row holds prev/next navigation when
 * more pages exist and a price-ticker icon (green dye). Each icon = the sell
 * item; lore shows owner + stall + trade + live trades-available (container
 * stock / sellAmount). Clicking an icon pastes the shop's coords into chat
 * (teleport is the admin verb, SP5).
 *
 * Results are pre-sorted cheapest-first by the caller to drive market competition.
 */
class SearchResultsMenu(
    private val results: List<Shop>,
    private val query: String,
    private val page: Int,
    private val lang: LangService,
    private val stallRepository: StallRepository,
    private val ticker: PriceTicker? = null,
) : Menu {

    @Suppress("LongMethod")
    override fun open(player: Player) {
        val totalPages = ((results.size + PER_PAGE - 1) / PER_PAGE).coerceAtLeast(1)
        val current = page.coerceIn(1, totalPages)
        val from = (current - 1) * PER_PAGE
        val pageItems = results.drop(from).take(PER_PAGE)

        // Batch-resolve stall region names (regionId like "stall1") for display
        val stallIds = pageItems.map { StallId(it.stallId) }.distinct()
        val stallNames = stallRepository.findByIds(stallIds)
            .mapValues { (_, stall) -> stall.regionId }

        val gui = ChestGui(ROWS, ComponentHolder.of(lang.msg("gui.shop.search.title", "query" to query)))
        val pane = StaticPane(9, ROWS)

        pageItems.forEachIndexed { idx, shop ->
            val template = ItemStackSerializer.deserialize(shop.sellItem)
            // Clone template to preserve stored enchantment NBT (e.g. enchanted books)
            val icon = template?.clone() ?: ItemStack(Material.CHEST)
            val meta = icon.itemMeta ?: return@forEachIndexed
            val owner = Bukkit.getOfflinePlayer(shop.owner).name ?: "Unknown"
            val dirLabel = ShopDisplay.directionLabel(shop.direction)
            val stallName = stallNames[StallId(shop.stallId)] ?: "Unknown"
            // Build enchantment suffix for enchanted books
            val enchantSuffix = template?.let { enchantSummary(it) }.orEmpty()
            meta.displayName(lang.msg(
                "gui.shop.search.result",
                "sell_amt" to shop.sellAmount, "cost" to shop.costAmount,
                "trades" to ShopDisplay.tradesAvailable(shop), "owner" to owner,
                "direction" to dirLabel, "stall" to stallName,
                "enchant" to enchantSuffix,
            ))
            icon.itemMeta = meta
            pane.addItem(GuiItem(icon) {
                it.isCancelled = true
                player.closeInventory()
                // Admins teleport to the shop; everyone else (and admins whose target
                // world isn't loaded) gets the coords pasted to chat so the click is
                // never a silent no-op.
                val world = if (player.hasPermission("enthusiamarket.admin.shop")) {
                    Bukkit.getWorld(shop.signWorld)
                } else {
                    null
                }
                if (world != null) {
                    player.teleport(org.bukkit.Location(
                        world, shop.signX + 0.5, shop.signY.toDouble(), shop.signZ + 0.5,
                        player.location.yaw, player.location.pitch,
                    ))
                    player.sendMessage(lang.msg("gui.shop.search.teleported",
                        "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ))
                } else {
                    player.sendMessage(lang.msg(
                        "gui.shop.search.clicked",
                        "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ,
                    ))
                }
            }, idx % 9, idx / 9)
        }

        // Bottom-row navigation (row index 5). Only shown when applicable.
        if (current > 1) {
            pane.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.shop.search.prev"))) {
                it.isCancelled = true
                SearchResultsMenu(results, query, current - 1, lang, stallRepository, ticker).open(player)
            }, 0, ROWS - 1)
        }
        if (current < totalPages) {
            pane.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.shop.search.next"))) {
                it.isCancelled = true
                SearchResultsMenu(results, query, current + 1, lang, stallRepository, ticker).open(player)
            }, 8, ROWS - 1)
        }

        // Price ticker icon (green dye) — bottom-right corner
        pane.addItem(GuiItem(tickerIcon()) {
            it.isCancelled = true
        }, 7, ROWS - 1)

        gui.addPane(pane)
        gui.blockItemTheft()
        gui.show(player)
    }

    private fun tickerIcon(): ItemStack {
        val item = ItemStack(Material.GREEN_DYE)
        val meta = item.itemMeta ?: return item

        val name = lang.msg("gui.shop.search.ticker_name")
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
        meta.displayName(name)

        val lore = mutableListOf<Component>()
        if (ticker != null) {
            lore += lang.msg("gui.shop.search.ticker_lore_avg",
                "avg" to "%,.1f".format(ticker.avgPrice),
                "count" to ticker.sampleCount,
            ).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
            lore += changeLine("24h", ticker.change24h)
            lore += changeLine("7d", ticker.change7d)
            lore += changeLine("30d", ticker.change30d)
        } else {
            // Fall back to average from current listings when no transaction history
            if (results.isNotEmpty()) {
                val avgListing = results.sumOf { it.costAmount.toDouble() / it.sellAmount.coerceAtLeast(1) } / results.size
                lore += lang.msg("gui.shop.search.ticker_lore_listing",
                    "avg" to "%,.1f".format(avgListing),
                    "count" to results.size,
                ).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
            }
            lore += lang.msg("gui.shop.search.ticker_lore_no_data")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
        }

        meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    private fun changeLine(label: String, pct: Double?): Component {
        if (pct == null) return lang.msg("gui.shop.search.ticker_lore_no_change", "label" to label)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
        val key = when {
            pct > 0.1 -> "gui.shop.search.ticker_lore_up"
            pct < -0.1 -> "gui.shop.search.ticker_lore_down"
            else -> "gui.shop.search.ticker_lore_flat"
        }
        return lang.msg(key, "label" to label, "change" to "%.1f".format(abs(pct)))
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
    }

    private fun named(material: Material, name: Component): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name)
        item.itemMeta = meta
        return item
    }

    companion object {
        /** Extract human-readable enchantment info from an ItemStack (e.g. "Sharpness V, Unbreaking III"). */
        fun enchantSummary(item: ItemStack): String {
            val meta = item.itemMeta ?: return ""
            // Enchanted books store enchants via EnchantmentStorageMeta
            if (meta is EnchantmentStorageMeta && meta.hasStoredEnchants()) {
                return " (" + meta.storedEnchants.entries.joinToString(", ") { (ench, level) ->
                    "${fmtEnchant(ench)} ${romanNumeral(level)}"
                } + ")"
            }
            // Regular enchanted items (swords, armor, etc.)
            if (meta.hasEnchants()) {
                return " (" + meta.enchants.entries.joinToString(", ") { (ench, level) ->
                    "${fmtEnchant(ench)} ${romanNumeral(level)}"
                } + ")"
            }
            return ""
        }

        private fun fmtEnchant(ench: Enchantment): String =
            ench.key.key.split('_').joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }

        private fun romanNumeral(n: Int): String = when (n) {
            1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"
            6 -> "VI"; 7 -> "VII"; 8 -> "VIII"; 9 -> "IX"; 10 -> "X"
            else -> n.toString()
        }
        private const val ROWS = 6
        private const val PER_PAGE = 45 // top 5 rows; bottom row reserved for nav
    }
}
