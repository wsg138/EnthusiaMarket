package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.PriceTicker
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.interaction.Menu
import net.badgersmc.em.interaction.blockItemTheft
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Locale
import kotlin.math.abs

/** Search results with stock filtering, sorting, and compact structured shop details. */
class SearchResultsMenu(
    private val results: List<Result>,
    private val query: String,
    private val lang: LangService,
    private val stallRepository: StallRepository,
    private val ticker: PriceTicker? = null,
    private val page: Int = 1,
    private val includeOutOfStock: Boolean = false,
    private val sort: Sort = Sort.PRICE_LOW,
) : Menu {

    data class Result(val shop: Shop, val matchedMaterial: Material, val nested: Boolean)

    enum class Sort { PRICE_LOW, PRICE_HIGH, STOCK_HIGH }

    override fun open(player: Player) {
        val visible = sort(filter(results, includeOutOfStock), sort)
        val totalPages = ((visible.size + PER_PAGE - 1) / PER_PAGE).coerceAtLeast(1)
        val current = page.coerceIn(1, totalPages)
        val pageItems = visible.drop((current - 1) * PER_PAGE).take(PER_PAGE)
        val stallNames = stallRepository.findByIds(pageItems.map { StallId(it.shop.stallId) }.distinct())
            .mapValues { it.value.regionId }

        val gui = ChestGui(ROWS, ComponentHolder.of(lang.msg("gui.shop.search.title", "query" to query)))
        val pane = StaticPane(9, ROWS)
        addControls(pane, player, current, totalPages, visible)
        addResultIcons(pane, player, pageItems, stallNames)

        gui.addPane(pane)
        gui.blockItemTheft()
        gui.show(player)
    }

    private fun addResultIcons(
        pane: StaticPane,
        player: Player,
        pageItems: List<Result>,
        stallNames: Map<StallId, String>,
    ) {
        pageItems.forEachIndexed { index, result ->
            val shop = result.shop
            val template = ItemStackSerializer.deserialize(shop.sellItem)
            val icon = template?.clone() ?: ItemStack(Material.CHEST)
            val meta = icon.itemMeta ?: return@forEachIndexed
            val owner = Bukkit.getOfflinePlayer(shop.owner).name ?: langText("gui.shop.search.unknown")
            val stall = stallNames[StallId(shop.stallId)] ?: shop.stallId
            val trades = ShopDisplay.tradesAvailable(shop)
            val statusKey = if (trades > 0) "gui.shop.search.in_stock" else "gui.shop.search.out_of_stock"
            meta.displayName(lang.msg(statusKey, "item" to pretty(result.matchedMaterial)))
            val stock = if (trades == Int.MAX_VALUE) langText("gui.shop.search.unlimited") else trades.toString()
            val lore = mutableListOf(
                lang.msg("gui.shop.search.location_heading"),
                lang.msg("gui.shop.search.world", "world" to shop.signWorld),
                lang.msg("gui.shop.search.coordinates", "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ),
                Component.empty(),
                lang.msg("gui.shop.search.owner", "owner" to owner),
                lang.msg("gui.shop.search.stall", "stall" to stall),
                lang.msg("gui.shop.search.price", "cost" to shop.costAmount, "amount" to shop.sellAmount),
                lang.msg("gui.shop.search.stock", "stock" to stock),
                lang.msg("gui.shop.search.direction", "direction" to ShopDisplay.directionLabel(shop.direction)),
            )
            if (result.nested) lore += lang.msg("gui.shop.search.contains", "item" to pretty(result.matchedMaterial))
            lore += Component.empty()
            lore += lang.msg(if (player.hasPermission(ADMIN_PERMISSION)) {
                "gui.shop.search.click_teleport"
            } else {
                "gui.shop.search.click_location"
            })
            meta.lore(lore.map(::plainStyle))
            icon.itemMeta = meta
            pane.addItem(GuiItem(icon) { event ->
                event.isCancelled = true
                player.closeInventory()
                val world = if (player.hasPermission(ADMIN_PERMISSION)) Bukkit.getWorld(shop.signWorld) else null
                if (world != null) {
                    player.teleport(Location(world, shop.signX + 0.5, shop.signY.toDouble(), shop.signZ + 0.5,
                        player.location.yaw, player.location.pitch))
                    player.sendMessage(lang.msg("gui.shop.search.teleported", "world" to shop.signWorld,
                        "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ))
                } else {
                    player.sendMessage(lang.msg("gui.shop.search.clicked", "world" to shop.signWorld,
                        "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ))
                }
            }, index % 9, RESULTS_START_ROW + index / 9)
        }
    }

    private fun addControls(pane: StaticPane, player: Player, current: Int, totalPages: Int, visible: List<Result>) {
        pane.addItem(GuiItem(named(Material.COMPARATOR, lang.msg("gui.shop.search.sort_name"), listOf(
            lang.msg("gui.shop.search.sort_selected", "sort" to langText(sort.langKey)),
            lang.msg("gui.shop.search.sort_click"),
        ))) {
            it.isCancelled = true
            copy(page = 1, sort = sort.next()).open(player)
        }, 2, 0)
        val filterMaterial = if (includeOutOfStock) Material.LIME_DYE else Material.GRAY_DYE
        pane.addItem(GuiItem(named(filterMaterial, lang.msg("gui.shop.search.stock_filter_name"), listOf(
            lang.msg(if (includeOutOfStock) "gui.shop.search.stock_filter_on" else "gui.shop.search.stock_filter_off"),
            lang.msg("gui.shop.search.stock_filter_click"),
        ))) {
            it.isCancelled = true
            copy(page = 1, includeOutOfStock = !includeOutOfStock).open(player)
        }, 6, 0)
        pane.addItem(GuiItem(named(Material.BARRIER, lang.msg("gui.shop.search.close"))) {
            it.isCancelled = true
            player.closeInventory()
        }, 8, 0)
        if (current > 1) pane.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.shop.search.prev"))) {
            it.isCancelled = true
            copy(page = current - 1).open(player)
        }, 0, ROWS - 1)
        pane.addItem(GuiItem(named(Material.PAPER, lang.msg("gui.shop.search.page",
            "page" to current, "pages" to totalPages, "count" to visible.size))) { it.isCancelled = true }, 4, ROWS - 1)
        pane.addItem(GuiItem(tickerIcon(visible.map { it.shop })) { it.isCancelled = true }, 6, ROWS - 1)
        if (current < totalPages) pane.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.shop.search.next"))) {
            it.isCancelled = true
            copy(page = current + 1).open(player)
        }, 8, ROWS - 1)
    }

    private fun copy(page: Int = this.page, includeOutOfStock: Boolean = this.includeOutOfStock, sort: Sort = this.sort) =
        SearchResultsMenu(results, query, lang, stallRepository, ticker, page, includeOutOfStock, sort)

    private fun tickerIcon(shops: List<Shop>): ItemStack {
        val lore = mutableListOf<Component>()
        if (ticker != null) {
            lore += lang.msg("gui.shop.search.ticker_lore_avg", "avg" to "%,.1f".format(ticker.avgPrice),
                "count" to ticker.sampleCount)
            lore += changeLine("24h", ticker.change24h)
            lore += changeLine("7d", ticker.change7d)
            lore += changeLine("30d", ticker.change30d)
        } else {
            if (shops.isNotEmpty()) {
                val average = shops.sumOf { unitPrice(it) } / shops.size
                lore += lang.msg("gui.shop.search.ticker_lore_listing", "avg" to "%,.1f".format(average),
                    "count" to shops.size)
            }
            lore += lang.msg("gui.shop.search.ticker_lore_no_data")
        }
        return named(Material.GREEN_DYE, lang.msg("gui.shop.search.ticker_name"), lore)
    }

    private fun changeLine(label: String, percent: Double?): Component {
        if (percent == null) return lang.msg("gui.shop.search.ticker_lore_no_change", "label" to label)
        val key = when { percent > 0.1 -> "gui.shop.search.ticker_lore_up"; percent < -0.1 ->
            "gui.shop.search.ticker_lore_down"; else -> "gui.shop.search.ticker_lore_flat" }
        return lang.msg(key, "label" to label, "change" to "%.1f".format(abs(percent)))
    }

    private fun named(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack =
        ItemStack(material).also { item -> item.itemMeta = item.itemMeta?.also { meta ->
            meta.displayName(plainStyle(name)); meta.lore(lore.map(::plainStyle))
        } }

    private fun langText(key: String): String = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
        .plainText().serialize(lang.msg(key))

    private fun plainStyle(component: Component): Component = component.decoration(TextDecoration.ITALIC, false)

    companion object {
        fun filter(results: List<Result>, includeOutOfStock: Boolean): List<Result> =
            if (includeOutOfStock) results else results.filter { ShopDisplay.tradesAvailable(it.shop) > 0 }

        fun sort(results: List<Result>, sort: Sort): List<Result> = when (sort) {
            Sort.PRICE_LOW -> results.sortedBy { unitPrice(it.shop) }
            Sort.PRICE_HIGH -> results.sortedByDescending { unitPrice(it.shop) }
            Sort.STOCK_HIGH -> results.sortedByDescending { ShopDisplay.tradesAvailable(it.shop) }
        }

        private fun unitPrice(shop: Shop): Double = shop.costAmount.toDouble() / shop.sellAmount.coerceAtLeast(1)
        private fun pretty(material: Material): String = material.name.lowercase(Locale.ROOT).split('_')
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        private val Sort.langKey: String get() = when (this) {
            Sort.PRICE_LOW -> "gui.shop.search.sort_price_low"
            Sort.PRICE_HIGH -> "gui.shop.search.sort_price_high"
            Sort.STOCK_HIGH -> "gui.shop.search.sort_stock_high"
        }
        private fun Sort.next(): Sort = Sort.values()[(ordinal + 1) % Sort.values().size]
        private const val ADMIN_PERMISSION = "enthusiamarket.admin.shop"
        private const val ROWS = 6
        private const val RESULTS_START_ROW = 1
        private const val PER_PAGE = 36
    }
}
