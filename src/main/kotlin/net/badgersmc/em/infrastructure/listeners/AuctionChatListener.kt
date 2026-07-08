package net.badgersmc.em.infrastructure.listeners

import io.papermc.paper.event.player.AsyncChatEvent
import net.badgersmc.em.application.AuctionLifecycleService
import net.badgersmc.em.interaction.gui.AuctionBidMenu
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/** Intercepts chat for custom auction bid amounts after the player clicks
 *  the "custom amount" button in [AuctionBidMenu]. */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class AuctionChatListener(
    private val lang: LangService,
    private val auctionService: AuctionLifecycleService,
) : Listener {

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (AuctionBidMenu.handleChat(event.player, message, lang, auctionService)) {
            event.isCancelled = true
        }
    }
}
