package net.badgersmc.em.infrastructure.listeners

import io.papermc.paper.event.player.AsyncChatEvent
import net.badgersmc.em.interaction.gui.CreateShopMenu
import net.badgersmc.em.interaction.gui.PurchaseBulkMenu
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin

/** Intercepts chat for custom price input after the player clicks
 *  the "Custom Price" button in [CreateShopMenu].
 *
 *  Runs on [AsyncChatEvent]; cancels the chat and schedules the
 *  actual menu opening on the main thread because [CreateShopMenu.open]
 *  fires [org.bukkit.event.inventory.InventoryOpenEvent] synchronously. */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class ChatPriceListener(
    private val lang: LangService,
    private val plugin: Plugin,
) : Listener {

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        val player = event.player
        // Check whether the player is waiting for a custom-price input.
        // handleChat removes from the pending set on first call, so we
        // must NOT call it on the async thread (would race with scheduling).
        if (CreateShopMenu.isWaiting(player.uniqueId)) {
            event.isCancelled = true
            Bukkit.getScheduler().runTask(plugin, Runnable {
                CreateShopMenu.handleChat(player, message, lang)
            })
        } else if (PurchaseBulkMenu.isWaiting(player.uniqueId)) {
            event.isCancelled = true
            Bukkit.getScheduler().runTask(plugin, Runnable {
                PurchaseBulkMenu.handleChat(player, message, lang)
            })
        }
    }
}
