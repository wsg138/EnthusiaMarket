package net.badgersmc.em.infrastructure.listeners

import io.papermc.paper.event.player.AsyncChatEvent
import net.badgersmc.em.interaction.gui.CreateShopMenu
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/** Intercepts chat for custom price input after the player clicks
 *  the "Custom Price" button in [CreateShopMenu]. */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class ChatPriceListener(
    private val lang: LangService,
) : Listener {

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (CreateShopMenu.handleChat(event.player, message, lang)) {
            event.isCancelled = true
        }
    }
}
