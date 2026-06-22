package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.badgersmc.em.interaction.blockItemTheft
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Click a shop icon to delete it (with a confirm click). First 45 shops. */
class DeleteShopsMenu(
    private val owner: UUID,
    private val management: ShopManagementService,
    private val lang: LangService,
) : Menu {
    private val armed = mutableSetOf<Long>()
    override fun open(player: Player) {
        val shops = management.shopsOwnedBy(owner).take(45)
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.shop.delete.title")))
        val pane = StaticPane(9, 6)
        shops.forEachIndexed { idx, shop ->
            val base = ItemStackSerializer.deserialize(shop.sellItem) ?: ItemStack(Material.CHEST)
            val meta = base.itemMeta
            if (meta != null) {
                val key = if (shop.id in armed) "gui.shop.delete.icon_armed" else "gui.shop.delete.icon"
                meta.displayName(lang.msg(key, "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ))
                base.itemMeta = meta
            }
            pane.addItem(GuiItem(base) {
                it.isCancelled = true
                if (shop.id in armed) {
                    management.delete(owner, shop.id)
                    armed.remove(shop.id)
                    player.sendMessage(lang.msg("shop.delete.done"))
                } else {
                    armed.add(shop.id)
                }
                open(player)
            }, idx % 9, idx / 9)
        }
        gui.addPane(pane)
        gui.blockItemTheft()
        gui.show(player)
    }
}