package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Paginated /shop search results. The first five rows (45 slots) hold result
 * icons for the requested page; the bottom row holds prev/next navigation when
 * more pages exist. Each icon = the sell item; lore shows owner + trade + live
 * trades-available (container stock / sellAmount). Clicking an icon pastes the
 * shop's coords into chat (teleport is the admin verb, SP5).
 */
class SearchResultsMenu(
    private val results: List<Shop>,
    private val query: String,
    private val page: Int,
    private val lang: LangService,
) : Menu {

    override fun open(player: Player) {
        val totalPages = ((results.size + PER_PAGE - 1) / PER_PAGE).coerceAtLeast(1)
        val current = page.coerceIn(1, totalPages)
        val from = (current - 1) * PER_PAGE
        val pageItems = results.drop(from).take(PER_PAGE)

        val gui = ChestGui(ROWS, ComponentHolder.of(lang.msg("gui.shop.search.title", "query" to query)))
        val pane = StaticPane(9, ROWS)

        pageItems.forEachIndexed { idx, shop ->
            val icon = ItemStackSerializer.deserialize(shop.sellItem) ?: ItemStack(Material.CHEST)
            val meta = icon.itemMeta
            if (meta != null) {
                val owner = Bukkit.getOfflinePlayer(shop.owner).name ?: "Unknown"
                meta.displayName(lang.msg(
                    "gui.shop.search.result",
                    "sell_amt" to shop.sellAmount, "cost" to shop.costAmount,
                    "trades" to tradesAvailable(shop), "owner" to owner,
                ))
                icon.itemMeta = meta
            }
            pane.addItem(GuiItem(icon) {
                it.isCancelled = true
                player.closeInventory()
                if (player.hasPermission("enthusiamarket.admin.shop")) {
                    val world = Bukkit.getWorld(shop.signWorld)
                    if (world != null) {
                        player.teleport(org.bukkit.Location(
                            world, shop.signX + 0.5, shop.signY.toDouble(), shop.signZ + 0.5,
                            player.location.yaw, player.location.pitch,
                        ))
                        player.sendMessage(lang.msg("gui.shop.search.teleported",
                            "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ))
                    }
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
                SearchResultsMenu(results, query, current - 1, lang).open(player)
            }, 0, ROWS - 1)
        }
        if (current < totalPages) {
            pane.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.shop.search.next"))) {
                it.isCancelled = true
                SearchResultsMenu(results, query, current + 1, lang).open(player)
            }, 8, ROWS - 1)
        }

        gui.addPane(pane)
        gui.show(player)
    }

    private fun named(material: Material, name: Component): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name)
        item.itemMeta = meta
        return item
    }

    private fun tradesAvailable(shop: Shop): Int {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return 0
        val state = world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state
        val inv = (state as? Container)?.inventory ?: return 0
        val sellStack = ItemStackSerializer.deserialize(shop.sellItem) ?: return 0
        val stock = inv.contents.filterNotNull().filter { it.isSimilar(sellStack) }.sumOf { it.amount }
        return stock / shop.sellAmount.coerceAtLeast(1)
    }

    private companion object {
        private const val ROWS = 6
        private const val PER_PAGE = 45 // top 5 rows; bottom row reserved for nav
    }
}
