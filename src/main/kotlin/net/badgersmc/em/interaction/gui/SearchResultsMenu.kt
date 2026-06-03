package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Paginated /shop search results (first 45). Each icon = the sell item; lore
 * shows owner, trade, and live trades-available (container stock / sellAmount).
 * Clicking pastes the shop's coords into chat (teleport is the admin verb, SP5).
 */
class SearchResultsMenu(
    private val results: List<Shop>,
    private val query: String,
    private val lang: LangService,
) : Menu {
    override fun open(player: Player) {
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.shop.search.title", "query" to query)))
        val pane = StaticPane(9, 6)
        results.take(45).forEachIndexed { idx, shop ->
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
                player.sendMessage(lang.msg(
                    "gui.shop.search.clicked",
                    "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ,
                ))
            }, idx % 9, idx / 9)
        }
        gui.addPane(pane)
        gui.show(player)
    }

    private fun tradesAvailable(shop: Shop): Int {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return 0
        val state = world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state
        val inv = (state as? Container)?.inventory ?: return 0
        val sellStack = ItemStackSerializer.deserialize(shop.sellItem) ?: return 0
        val stock = inv.contents.filterNotNull().filter { it.isSimilar(sellStack) }.sumOf { it.amount }
        return stock / shop.sellAmount.coerceAtLeast(1)
    }
}
