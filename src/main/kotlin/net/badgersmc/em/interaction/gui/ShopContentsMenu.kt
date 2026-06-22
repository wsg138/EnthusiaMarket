package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.badgersmc.em.interaction.blockItemTheft
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Read-only mirror of a shop's container inventory (IS2-13, REQ-299).
 * 54-slot layout (double-chest sized). All clicks cancelled.
 */
class ShopContentsMenu(
    private val shop: Shop,
    private val lang: LangService,
) : Menu {

    override fun open(player: Player) {
        val container = loadedContainer(shop)
        val contents = container?.inventory?.contents ?: emptyArray()

        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.shop_contents.title")))
        val pane = StaticPane(9, 6)
        gui.addPane(pane)

        for ((idx, stack) in contents.withIndex()) {
            if (stack == null || stack.type == Material.AIR) continue
            val icon = stack.clone()
            pane.addItem(GuiItem(icon) { it.isCancelled = true }, idx % 9, idx / 9)
        }

        gui.blockItemTheft()
        gui.show(player)
    }

    /** The shop's container block state, only if its chunk is already loaded; null otherwise. */
    private fun loadedContainer(shop: Shop): org.bukkit.block.Container? {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return null
        if (!world.isChunkLoaded(shop.containerX shr 4, shop.containerZ shr 4)) return null
        return world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state
            as? org.bukkit.block.Container
    }
}