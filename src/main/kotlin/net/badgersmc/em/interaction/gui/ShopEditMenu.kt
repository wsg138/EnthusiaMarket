package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * IFramework GUI for editing a shop's settings (REQ-023).
 * Owner can freeze/unfreeze, configure hopper, and manage trust.
 */
class ShopEditMenu(
    private val shop: Shop,
    private val shopRepository: ShopRepository,
    private val lang: LangService
) : Menu {

    override fun open(player: Player) {
        if (player.uniqueId != shop.owner && !player.hasPermission("enthusiamarket.admin")) {
            player.sendMessage(lang.msg("shop.edit.not_owner"))
            return
        }
        val gui = ChestGui(3, ComponentHolder.of(lang.msg("gui.edit.title")))
        val pane = StaticPane(9, 3)

        // Slot 11: Freeze toggle
        val freezeStack = if (shop.frozen) {
            decorated(
                Material.RED_STAINED_GLASS_PANE,
                lang.msg("gui.edit.frozen_name"),
                listOf(
                    lang.msg("gui.edit.frozen_lore_blocked"),
                    Component.empty(),
                    lang.msg("gui.edit.frozen_lore_click_unfreeze")
                )
            )
        } else {
            decorated(
                Material.LIME_STAINED_GLASS_PANE,
                lang.msg("gui.edit.active_name"),
                listOf(
                    lang.msg("gui.edit.active_lore_allowed"),
                    Component.empty(),
                    lang.msg("gui.edit.active_lore_click_freeze")
                )
            )
        }
        pane.addItem(GuiItem(freezeStack) { event ->
            event.isCancelled = true
            val updated = shop.copy(frozen = !shop.frozen)
            shopRepository.upsert(updated)
            player.sendMessage(lang.msg(if (updated.frozen) "shop.edit.frozen" else "shop.edit.unfrozen"))
            ShopEditMenu(updated, shopRepository, lang).open(player)
        }, 2, 1)

        // Slot 13: Hopper In toggle
        val hopperInStack = if (shop.hopperAllowIn) {
            decorated(
                Material.HOPPER,
                lang.msg("gui.edit.hopper_in_on"),
                listOf(lang.msg("gui.edit.hopper_in_on_lore_1"), lang.msg("gui.edit.hopper_in_on_lore_2"))
            )
        } else {
            decorated(
                Material.HOPPER,
                lang.msg("gui.edit.hopper_in_off"),
                listOf(lang.msg("gui.edit.hopper_in_off_lore_1"), lang.msg("gui.edit.hopper_in_off_lore_2"))
            )
        }
        pane.addItem(GuiItem(hopperInStack) { event ->
            event.isCancelled = true
            val updated = shop.copy(hopperAllowIn = !shop.hopperAllowIn)
            shopRepository.upsert(updated)
            ShopEditMenu(updated, shopRepository, lang).open(player)
        }, 4, 1)

        // Slot 15: Hopper Out toggle
        val hopperOutStack = if (shop.hopperAllowOut) {
            decorated(
                Material.HOPPER_MINECART,
                lang.msg("gui.edit.hopper_out_on"),
                listOf(lang.msg("gui.edit.hopper_out_on_lore_1"), lang.msg("gui.edit.hopper_out_on_lore_2"))
            )
        } else {
            decorated(
                Material.HOPPER_MINECART,
                lang.msg("gui.edit.hopper_out_off"),
                listOf(lang.msg("gui.edit.hopper_out_off_lore_1"), lang.msg("gui.edit.hopper_out_off_lore_2"))
            )
        }
        pane.addItem(GuiItem(hopperOutStack) { event ->
            event.isCancelled = true
            val updated = shop.copy(hopperAllowOut = !shop.hopperAllowOut)
            shopRepository.upsert(updated)
            ShopEditMenu(updated, shopRepository, lang).open(player)
        }, 6, 1)

        // Slot 22: Trust management
        val trustStack = decorated(
            Material.PLAYER_HEAD,
            lang.msg("gui.edit.manage_trusted"),
            listOf(
                lang.msg("gui.edit.manage_trusted_lore_count", "count" to shop.trusted.size),
                lang.msg("gui.edit.manage_trusted_lore_click")
            )
        )
        pane.addItem(GuiItem(trustStack) { event ->
            event.isCancelled = true
            TrustManageMenu(player, shop, shopRepository, lang).open(player)
        }, 4, 2)

        gui.addPane(pane)
        gui.show(player)
    }

    private fun decorated(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name)
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }
}
