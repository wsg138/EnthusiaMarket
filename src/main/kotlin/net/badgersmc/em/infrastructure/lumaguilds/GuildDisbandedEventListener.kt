package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.nexus.annotations.Component
import org.bukkit.event.Event

/** Forwards compatible LumaGuilds disband events to the guild provider. */
@Component
class GuildDisbandedEventListener(
    private val provider: LumaGuildsGuildProvider,
) {
    fun onGuildDisbanded(event: Event) {
        val guildId = LumaGuildsEventAccess.guildId(event) ?: return
        provider.handleDisbanded(guildId.toString())
    }
}
