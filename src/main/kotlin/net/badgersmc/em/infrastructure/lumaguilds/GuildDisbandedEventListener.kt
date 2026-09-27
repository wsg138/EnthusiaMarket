package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.nexus.annotations.Component
import org.bukkit.event.Event

/** Forwards LumaGuilds disband events without binding to one event package version. */
@Component
class GuildDisbandedEventListener(
    private val provider: LumaGuildsGuildProvider,
) {
    fun onGuildDisbanded(event: Event) {
        val guildId = LumaGuildsEventAccess.guildId(event) ?: return
        provider.handleDisbanded(guildId.toString())
    }
}
