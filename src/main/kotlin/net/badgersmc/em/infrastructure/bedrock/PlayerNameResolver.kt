package net.badgersmc.em.infrastructure.bedrock

import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.geysermc.floodgate.api.FloodgateApi

/**
 * Resolves a player name (as typed in a command) to an [OfflinePlayer] that
 * has actually played before, or `null` when the name can't be matched.
 *
 * Bedrock players join via Floodgate with a prefixed username (e.g. `*okcosmo`
 * on EnthusiaSMP where `username-prefix: "*"`), so a Java player typing the
 * bare name (`okcosmo`) gets an usercache miss and a fake offline UUID that
 * never played. This resolver retries with the Floodgate prefix appended, and
 * also strips a prefix the user already typed.
 */
@Component
class PlayerNameResolver {

    /** Resolve [name] using the live Floodgate prefix when Floodgate is available. */
    fun resolve(name: String): OfflinePlayer? = resolve(name, floodgatePrefix())

    /**
     * Resolve [name] to a real player, or `null` when no variant matches a
     * player that has played before. The explicit [prefix] overload keeps the
     * lookup rules testable without making a function dependency visible to DI.
     */
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
            null // Floodgate not installed / not loaded — no prefix fallback
        }
    }
}
