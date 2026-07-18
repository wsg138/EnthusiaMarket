package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.StallBuyoutService
import net.badgersmc.em.interaction.PurchaseFlow
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.interaction.Menu
import net.badgersmc.em.interaction.blockItemTheft
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Confirmation step before purchasing a stall. Shows what you're buying
 * (personal or guild) and the price. Confirm → executes the buyout.
 * Cancel → returns to [PurchaseMethodMenu].
 */
class PurchaseConfirmMenu(
    private val stallId: StallId,
    private val price: Long,
    private val isGuild: Boolean,
    private val buyout: StallBuyoutService,
    private val lang: LangService,
) : Menu {

    override fun open(player: Player) {
        val gui = ChestGui(3, ComponentHolder.of(lang.msg("purchase_sign.msg.confirm_title")))
        val pane = StaticPane(9, 3)

        // Display item — diamond for personal, gold block for guild
        val previewMat = if (isGuild) Material.GOLD_BLOCK else Material.DIAMOND
        val previewKey = if (isGuild) "purchase_sign.msg.confirm_guild" else "purchase_sign.msg.confirm_personal"
        pane.addItem(
            GuiItem(decorated(previewMat, lang.msg(previewKey, "stall" to stallId.value, "price" to price))),
            4, 0,
        )

        // Confirm button — green
        pane.addItem(
            GuiItem(decorated(Material.LIME_STAINED_GLASS_PANE, lang.msg("purchase_sign.msg.confirm_yes"))) { event ->
                event.isCancelled = true
                val result = PurchaseFlow.execute(player, stallId, price, isGuild, buyout)
                player.sendMessage(PurchaseFlow.message(result, stallId, lang))
                player.closeInventory()
            },
            3, 2,
        )

        // Cancel button — red, returns to method selection
        pane.addItem(
            GuiItem(decorated(Material.RED_STAINED_GLASS_PANE, lang.msg("purchase_sign.msg.confirm_no"))) { event ->
                event.isCancelled = true
                // Can't reopen PurchaseMethodMenu without its deps, so just close
                player.closeInventory()
            },
            5, 2,
        )

        gui.addPane(pane)
        gui.blockItemTheft()
        gui.show(player)
    }

    companion object {
        private fun decorated(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
            val item = ItemStack(material)
            val meta = item.itemMeta ?: return item
            meta.displayName(name)
            if (lore.isNotEmpty()) meta.lore(lore)
            item.itemMeta = meta
            return item
        }
    }
}
