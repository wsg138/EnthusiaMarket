package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

/** Owns conditional registration of listeners that depend on the LumaGuilds event API. */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
class LumaGuildsListenerRegistration(
    private val plugin: JavaPlugin,
    private val config: EnthusiaMarketConfig,
    private val disbanded: GuildDisbandedEventListener,
    private val visualChanges: GuildVisualChangeListener,
) : Listener {

    @PostConstruct
    fun registerConfiguredListeners() {
        val pluginManager = plugin.server.pluginManager
        val available = pluginManager.isPluginEnabled(LUMA_GUILDS)
        if (!shouldRegister(config.lumaguilds.enabled, available)) {
            plugin.logger.info("LumaGuilds event integration disabled; guild event listeners were not registered")
            return
        }
        pluginManager.registerEvents(disbanded, plugin)
        pluginManager.registerEvents(visualChanges, plugin)
    }

    companion object {
        private const val LUMA_GUILDS = "LumaGuilds"

        internal fun shouldRegister(configured: Boolean, available: Boolean): Boolean = configured && available
    }
}
