package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.StallBuyoutService
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.interaction.Menu
import net.badgersmc.em.interaction.blockItemTheft
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID
import java.util.logging.Logger

/**
 * IFramework GUI shown when a player right-clicks a purchase sign on an
 * UNOWNED stall. Offers two options:
 *
 * - Steve head → personal buyout
 * - Guild banner → guild buyout (visible only when the player is in a
 *   guild and has MANAGE_SHOPS permission)
 *
 * Selecting an option opens [PurchaseConfirmMenu].
 */
class PurchaseMethodMenu(
    private val stallId: StallId,
    private val price: Long,
    private val buyout: StallBuyoutService,
    private val guildProvider: GuildProvider?,
    private val lang: LangService,
) : Menu {

    override fun open(player: Player) {
        val gui = ChestGui(
            3,
            ComponentHolder.of(lang.msg("purchase_sign.msg.method_title", "stall" to stallId.value, "price" to price)),
        )
        val pane = StaticPane(9, 3)

        // Personal option — Steve head
        val head = ItemStack(Material.PLAYER_HEAD)
        val skullMeta = head.itemMeta as? SkullMeta
        if (skullMeta != null) {
            try {
                skullMeta.owningPlayer = player
            } catch (_: Exception) {
                // If offline player head fails, just leave the default skull
            }
            head.itemMeta = skullMeta
        }
        pane.addItem(
            GuiItem(decorated(head, lang.msg("purchase_sign.msg.method_personal"), listOf(lang.msg("purchase_sign.msg.method_personal_lore")))) { event ->
                event.isCancelled = true
                PurchaseConfirmMenu(stallId, price, isGuild = false, buyout, lang).open(player)
            },
            3, 1,
        )

        // Guild option — only visible when eligible
        val guild = guildProvider?.guildOf(player.uniqueId)
        val canBuyForGuild = guild != null &&
            guildProvider != null &&
            guildProvider.hasShopPermission(player.uniqueId, guild.id, GuildProvider.GuildPermission.MANAGE_SHOPS)

        if (canBuyForGuild && guild != null) {
            val banner = ItemStack(Material.WHITE_BANNER)
            pane.addItem(
                GuiItem(decorated(banner, lang.msg("purchase_sign.msg.method_guild"), listOf(
                    lang.msg("purchase_sign.msg.method_guild_lore"),
                    Component.text("§7Guild: §6${guild.name}"),
                ))) { event ->
                    event.isCancelled = true
                    PurchaseConfirmMenu(stallId, price, isGuild = true, buyout, lang).open(player)
                },
                5, 1,
            )
        }

        // Cancel button
        pane.addItem(
            GuiItem(decorated(Material.BARRIER, lang.msg("purchase_sign.msg.confirm_no"))) { event ->
                event.isCancelled = true
                player.closeInventory()
            },
            4, 2,
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

        private fun decorated(base: ItemStack, name: Component, lore: List<Component> = emptyList()): ItemStack {
            val item = base.clone()
            val meta = item.itemMeta ?: return item
            meta.displayName(name)
            if (lore.isNotEmpty()) meta.lore(lore)
            item.itemMeta = meta
            return item
        }
    }
}
