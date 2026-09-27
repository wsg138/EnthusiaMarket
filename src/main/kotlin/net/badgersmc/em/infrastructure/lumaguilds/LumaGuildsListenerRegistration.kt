package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
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
        val lumaGuilds = pluginManager.getPlugin(LUMA_GUILDS)
        if (!shouldRegister(config.lumaguilds.enabled, lumaGuilds?.isEnabled == true)) {
            plugin.logger.info("LumaGuilds event integration disabled; guild event listeners were not registered")
            return
        }

        pluginManager.registerEvents(visualChanges, plugin)
        val loader = requireNotNull(lumaGuilds).javaClass.classLoader
        registerEvent(loader, "GuildDisbandedEvent", disbanded::onGuildDisbanded)
        registerEvent(loader, "GuildBannerChangedEvent", visualChanges::onGuildVisualChanged)
        registerEvent(loader, "GuildOwnershipTransferEvent", visualChanges::onGuildVisualChanged)
    }

    private fun registerEvent(classLoader: ClassLoader, simpleName: String, handler: (Event) -> Unit) {
        val eventType = LumaGuildsEventAccess.resolve(classLoader, simpleName)
        if (eventType == null) {
            plugin.logger.warning("LumaGuilds event $simpleName is unavailable; related Market refreshes are disabled")
            return
        }
        val executor = EventExecutor { _, event -> handler(event) }
        plugin.server.pluginManager.registerEvent(
            eventType,
            this,
            EventPriority.MONITOR,
            executor,
            plugin,
            true,
        )
    }

    companion object {
        private const val LUMA_GUILDS = "LumaGuilds"

        internal fun shouldRegister(configured: Boolean, available: Boolean): Boolean = configured && available
    }
}
