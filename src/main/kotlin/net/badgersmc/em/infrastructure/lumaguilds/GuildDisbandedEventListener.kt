package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.nexus.annotations.Component
import net.lumalyte.lg.domain.events.GuildDisbandedEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/**
 * Bukkit [Listener] that observes [GuildDisbandedEvent] from LumaGuilds
 * and forwards notifications to [LumaGuildsGuildProvider]'s dissolve handlers.
 *
 * Must be registered with Bukkit's [org.bukkit.plugin.PluginManager] during plugin
 * startup (e.g. in the main plugin class's onEnable).
 */
@Component
class GuildDisbandedEventListener(
    private val provider: LumaGuildsGuildProvider,
) : Listener {

    @EventHandler
    fun onGuildDisbanded(event: GuildDisbandedEvent) {
        provider.handleDisbanded(event.guild.id.toString())
    }
}