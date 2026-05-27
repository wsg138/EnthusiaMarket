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
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/**
 * IFramework GUI for managing a shop's trusted players (REQ-018).
 * Shows current trusted players with remove buttons, and an add button.
 */
class TrustManageMenu(
    private val player: Player,
    private val shop: Shop,
    private val shopRepository: ShopRepository,
    private val lang: LangService
) : Menu {

    override fun open(player: Player) {
        val rows = 3 + ((shop.trusted.size + 8) / 9).coerceAtMost(4)
        val gui = ChestGui(rows.coerceAtMost(6), ComponentHolder.of(lang.msg("gui.trust.title")))

        val pane = StaticPane(9, rows.coerceAtMost(6))
        var slot = 0

        val maxEntries = minOf(shop.trusted.size, rows * 9 - 9)
        val entries = shop.trusted.take(maxEntries)

        for (trustedUuid in entries) {
            val head = ItemStack(Material.PLAYER_HEAD)
            val meta = head.itemMeta as? SkullMeta
            meta?.setOwningPlayer(Bukkit.getOfflinePlayer(trustedUuid))
            val name = Bukkit.getOfflinePlayer(trustedUuid).name ?: "Unknown"
            meta?.displayName(lang.msg("gui.trust.member_name", "name" to name))
            meta?.lore(listOf(
                lang.msg("gui.trust.member_lore_uuid", "uuid" to trustedUuid),
                Component.empty(),
                lang.msg("gui.trust.member_lore_remove")
            ))
            head.itemMeta = meta

            val uuid = trustedUuid
            pane.addItem(GuiItem(head) { event ->
                event.isCancelled = true
                val updated = shop.copy(trusted = shop.trusted - uuid)
                shopRepository.upsert(updated)
                player.sendMessage(lang.msg("shop.trust.removed"))
                TrustManageMenu(player, updated, shopRepository, lang).open(player)
            }, slot % 9, slot / 9)
            slot++
        }

        val addStack = ItemStack(Material.LIME_STAINED_GLASS_PANE)
        addStack.itemMeta = addStack.itemMeta?.apply {
            displayName(lang.msg("gui.trust.add_name"))
            lore(listOf(lang.msg("gui.trust.add_lore")))
        }
        pane.addItem(GuiItem(addStack) { event ->
            event.isCancelled = true
            player.sendMessage(lang.msg("shop.trust.open_chat_prompt"))
        }, 4, rows.coerceAtMost(6) - 2)

        gui.addPane(pane)
        gui.show(player)
    }
}
