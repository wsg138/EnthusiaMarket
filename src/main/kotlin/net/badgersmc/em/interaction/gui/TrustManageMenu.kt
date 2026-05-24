package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.interaction.Menu
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID

/**
 * IFramework GUI for managing a shop's trusted players (REQ-018).
 * Shows current trusted players with remove buttons, and an add button.
 */
class TrustManageMenu(
    private val player: Player,
    private val shop: Shop,
    private val shopRepository: ShopRepository
) : Menu {

    override fun open() {
        // Dynamic rows: 3 for header+add, then 1 per 9 trusted players
        val rows = 3 + ((shop.trusted.size + 8) / 9).coerceAtLeast(1)
        val gui = ChestGui(rows.coerceAtMost(6), "§8Manage Trusted Players")

        val pane = StaticPane(9, rows.coerceAtMost(6))
        var slot = 0

        // Header: current trusted players
        for (trustedUuid in shop.trusted) {
            val head = ItemStack(Material.PLAYER_HEAD)
            val meta = head.itemMeta as? SkullMeta
            meta?.setOwningPlayer(Bukkit.getOfflinePlayer(trustedUuid))
            meta?.setDisplayName("§e${Bukkit.getOfflinePlayer(trustedUuid).name ?: "Unknown"}")
            meta?.lore = listOf("§7UUID: $trustedUuid", "", "§cLeft-click to remove")
            head.itemMeta = meta

            val uuid = trustedUuid
            pane.addItem(GuiItem(head, { event ->
                event.isCancelled = true
                val updated = shop.copy(trusted = shop.trusted - uuid)
                shopRepository.upsert(updated)
                player.sendMessage("§aRemoved trusted player")
                // Reopen the menu
                TrustManageMenu(player, updated, shopRepository).open()
            }), slot % 9, slot / 9)
            slot++
        }

        // Add button at bottom center
        val addStack = ItemStack(Material.LIME_STAINED_GLASS_PANE)
        addStack.itemMeta = addStack.itemMeta?.apply {
            setDisplayName("§a§lADD TRUSTED PLAYER")
            lore = listOf("§7Type a player name in chat to add")
        }
        pane.addItem(GuiItem(addStack, { event ->
            event.isCancelled = true
            player.sendMessage("§eType the name of the player to trust in chat")
            // Chat capture would be handled by a separate ChatAmountCapture mechanism
            // For now just a placeholder
        }), 4, rows.coerceAtMost(6) - 2)

        gui.addPane(pane)
        gui.show(player)
    }
}
