package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.badgersmc.em.interaction.blockItemTheft
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Paginated /shop search results. The first five rows (45 slots) hold result
 * icons for the requested page; the bottom row holds prev/next navigation when
 * more pages exist. Each icon = the sell item; lore shows owner + stall + trade
 * + live trades-available (container stock / sellAmount). Clicking an icon
 * pastes the shop's coords into chat (teleport is the admin verb, SP5).
 */
class SearchResultsMenu(
    private val results: List<Shop>,
    private val query: String,
    private val page: Int,
    private val lang: LangService,
    private val stallRepository: StallRepository,
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
            // Use a clean stack from the material so the original display name and
            // enchantment lore don't overlay the search-result metadata
            val icon = ItemStack(template?.type ?: Material.CHEST)
            val meta = icon.itemMeta ?: return@forEachIndexed
            val owner = Bukkit.getOfflinePlayer(shop.owner).name ?: "Unknown"
            val dirLabel = ShopDisplay.directionLabel(shop.direction)
            val stallName = stallNames[StallId(shop.stallId)] ?: "Unknown"
            meta.displayName(lang.msg(
                "gui.shop.search.result",
                "sell_amt" to shop.sellAmount, "cost" to shop.costAmount,
                "trades" to ShopDisplay.tradesAvailable(shop), "owner" to owner,
                "direction" to dirLabel, "stall" to stallName,
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
                SearchResultsMenu(results, query, current - 1, lang, stallRepository).open(player)
            }, 0, ROWS - 1)
        }
        if (current < totalPages) {
            pane.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.shop.search.next"))) {
                it.isCancelled = true
                SearchResultsMenu(results, query, current + 1, lang, stallRepository).open(player)
            }, 8, ROWS - 1)
        }

        gui.addPane(pane)
        gui.blockItemTheft()
        gui.show(player)
    }

    private fun named(material: Material, name: Component): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name)
        item.itemMeta = meta
        return item
    }

    private companion object {
        private const val ROWS = 6
        private const val PER_PAGE = 45 // top 5 rows; bottom row reserved for nav
    }
}
