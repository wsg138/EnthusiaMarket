package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Pick which of the owner's shops to trust/untrust [targetName] on. Clicking a
 * shop icon toggles its selection; the confirm button applies via
 * [ShopManagementService]. Up to 45 shops (one chest page) — owners with more
 * use `/shop trust <player> all`.
 */
class BulkTrustMenu(
    private val owner: UUID,
    private val target: UUID,
    private val targetName: String,
    private val management: ShopManagementService,
    private val lang: LangService,
) : Menu {

    private val selected = mutableSetOf<Long>()

    override fun open(player: Player) {
        val shops = management.shopsOwnedBy(owner).take(45)
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.shop.trust.title", "name" to targetName)))
        val pane = StaticPane(9, 6)
        shops.forEachIndexed { idx, shop ->
            pane.addItem(GuiItem(icon(shop, shop.id in selected)) {
                it.isCancelled = true
                if (shop.id in selected) selected.remove(shop.id) else selected.add(shop.id)
                open(player) // re-render
            }, idx % 9, idx / 9)
        }
        pane.addItem(GuiItem(named(Material.LIME_STAINED_GLASS_PANE, lang.msg("gui.shop.trust.confirm"))) {
            it.isCancelled = true
            val n = management.trust(owner, target, selected.toList())
            player.closeInventory()
            player.sendMessage(lang.msg("shop.cmd.trusted_all", "name" to targetName, "count" to n))
        }, 8, 5)
        gui.addPane(pane)
        gui.show(player)
    }

    private fun icon(shop: Shop, sel: Boolean): ItemStack {
        val base = ItemStackSerializer.deserialize(shop.sellItem) ?: ItemStack(Material.CHEST)
        val meta = base.itemMeta ?: return base
        meta.displayName(lang.msg("gui.shop.trust.icon", "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ, "sel" to sel))
        base.itemMeta = meta
        return base
    }

    private fun named(material: Material, name: Component): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name)
        item.itemMeta = meta
        return item
    }
}