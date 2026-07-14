package net.badgersmc.em.interaction.gui

import net.badgersmc.nexus.paper.listeners.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.player.AsyncPlayerChatEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Listener that parses a chat message as a custom price/amount for [CreateShopMenu].
 * Registered via Nexus DI with the [Listener] annotation.
 *
 * Each prompt expires after [TIMEOUT_SECONDS] — typing nothing (or any message that
 * isn't the price prompt) removes the player from the waiting set so the chat
 * interception doesn't outlast the menu.
 */
@Listener
class ChatPriceListener : org.bukkit.event.Listener {
    companion object {
        private const val TIMEOUT_SECONDS = 60L

        /** (playerUUID → menu). Populated on prompt, cleared on answer or expiry. */
        val waiting = ConcurrentHashMap<UUID, CreateShopMenu>()

        internal fun register(playerId: UUID, menu: CreateShopMenu, plugin: org.bukkit.plugin.Plugin) {
            waiting[playerId] = menu
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                waiting.remove(playerId)?.let {
                    it.internalNotifyTimeout(playerId)
                }
            }, TimeUnit.SECONDS.toSeconds(TIMEOUT_SECONDS) * 20L)
        }
    }

    @EventHandler
    fun onChat(event: AsyncPlayerChatEvent) {
        val menu = waiting.remove(event.player.uniqueId) ?: return
        event.isCancelled = true
        val input = event.message.trim()
        if (input.equals("cancel", ignoreCase = true)) {
            event.player.sendMessage(menu.internalLang.msg("gui.shop.create.custom_price_cancelled"))
            org.bukkit.Bukkit.getScheduler().runTask(
                org.bukkit.Bukkit.getPluginManager().getPlugin("EnthusiaMarket")!!,
                Runnable { menu.open(event.player) }
            )
            return
        }
        val parsed = input.toLongOrNull()
        if (parsed == null || parsed < 1) {
            event.player.sendMessage(menu.internalLang.msg("gui.shop.create.custom_price_invalid"))
            waiting[event.player.uniqueId] = menu
            return
        }
        menu.setPrice(parsed)
        org.bukkit.Bukkit.getScheduler().runTask(
            org.bukkit.Bukkit.getPluginManager().getPlugin("EnthusiaMarket")!!,
            Runnable { menu.open(event.player) }
        )
    }
}
