package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.java.JavaPlugin

/** Owns conditional registration of the version-sensitive LumaGuilds events. */
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
        if (!config.lumaguilds.enabled) {
            plugin.logger.info("LumaGuilds event integration disabled by configuration")
            return
        }

        val pluginManager = plugin.server.pluginManager
        val lumaGuilds = pluginManager.getPlugin(LUMA_GUILDS)
        if (!shouldRegister(configured = true, available = lumaGuilds?.isEnabled == true)) {
            plugin.logger.warning("LumaGuilds event integration unavailable; no guild event listeners registered")
            return
        }

        val classLoader = requireNotNull(lumaGuilds).javaClass.classLoader
        registerEvent(pluginManager, classLoader, DISBANDED_EVENT, disbanded::onGuildDisbanded)
        registerEvent(pluginManager, classLoader, BANNER_CHANGED_EVENT, visualChanges::onGuildVisualChanged)
        registerEvent(pluginManager, classLoader, OWNERSHIP_TRANSFER_EVENT, visualChanges::onGuildVisualChanged)
    }

    private fun registerEvent(
        pluginManager: PluginManager,
        classLoader: ClassLoader,
        simpleName: String,
        handler: (Event) -> Unit,
    ) {
        val eventType = LumaGuildsEventAccess.resolve(classLoader, simpleName)
        if (eventType == null) {
            plugin.logger.warning("LumaGuilds event $simpleName is unavailable; related Market refreshes are disabled")
            return
        }
        val executor = EventExecutor { _, event -> handler(event) }
        pluginManager.registerEvent(eventType, this, EventPriority.MONITOR, executor, plugin, true)
    }

    companion object {
        private const val LUMA_GUILDS = "LumaGuilds"
        private const val DISBANDED_EVENT = "GuildDisbandedEvent"
        private const val BANNER_CHANGED_EVENT = "GuildBannerChangedEvent"
        private const val OWNERSHIP_TRANSFER_EVENT = "GuildOwnershipTransferEvent"

        internal fun shouldRegister(configured: Boolean, available: Boolean): Boolean = configured && available
    }
}
