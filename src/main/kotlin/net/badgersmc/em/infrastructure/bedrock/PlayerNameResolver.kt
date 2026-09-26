package net.badgersmc.em.infrastructure.bedrock

import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.geysermc.floodgate.api.FloodgateApi

/** Resolves Java and Floodgate-prefixed player names without DI constructor dependencies. */
@Component
class PlayerNameResolver {

    fun resolve(name: String): OfflinePlayer? = resolve(name, floodgatePrefix())

    internal fun resolve(name: String, prefix: String?): OfflinePlayer? {
        exact(name)?.let { return it }
        if (!prefix.isNullOrEmpty()) {
            exact(prefix + name)?.let { return it }
            if (name.startsWith(prefix)) {
                exact(name.removePrefix(prefix))?.let { return it }
            }
        }
        return null
    }

    private fun exact(name: String): OfflinePlayer? {
        val player = Bukkit.getOfflinePlayer(name)
        return if (player.hasPlayedBefore()) player else null
    }

    private companion object {
        fun floodgatePrefix(): String? = try {
            FloodgateApi.getInstance().playerPrefix
        } catch (_: Throwable) {
            null
        }
    }
}
