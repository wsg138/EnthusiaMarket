package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.badgersmc.em.interaction.blockItemTheft
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta

/**
 * Read-only preview of a shulker box's contents (IS2-12, REQ-298).
 * 27-slot layout matching shulker box inventory size.
 */
class ShulkerPreviewMenu(
    private val shulker: ItemStack,
    private val lang: LangService,
) : Menu {

    override fun open(player: Player) {
        val shulkerState = (shulker.itemMeta as? BlockStateMeta)?.blockState as? org.bukkit.block.ShulkerBox
        val contents = shulkerState?.inventory?.contents ?: emptyArray()

        val gui = ChestGui(3, ComponentHolder.of(lang.msg("gui.shulker_preview.title")))
        val pane = StaticPane(9, 3)
        gui.addPane(pane)

        for ((idx, stack) in contents.withIndex()) {
            if (stack == null || stack.type == Material.AIR) continue
            val icon = stack.clone()
            pane.addItem(GuiItem(icon) { it.isCancelled = true }, idx % 9, idx / 9)
        }

        gui.blockItemTheft()
        gui.show(player)
    }
}