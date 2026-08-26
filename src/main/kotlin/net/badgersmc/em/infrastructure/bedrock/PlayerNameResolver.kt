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
 * also strips a prefix the user already typed — mirroring LumaGuilds'
 * `findPlayerByName` fix for the same class of bug.
 */
@Component
class PlayerNameResolver(
    /** Supplies the current Floodgate username prefix (e.g. `*`), or null when unavailable. */
    private val floodgatePrefixProvider: () -> String? = { floodgatePrefix() },
) {

    /**
     * Resolve [name] to a real player, or `null` when no variant matches a
     * player that has played before. Tries, in order:
     *
     * 1. exact name (`okcosmo`)
     * 2. Floodgate prefix + name (`*okcosmo`)
     * 3. name with a leading Floodgate prefix stripped (`*okcosmo` → `okcosmo`)
     *
     * A variant only counts when `OfflinePlayer.hasPlayedBefore()` is true —
     * the same validity predicate the command handlers used before, so
     * Java-player behaviour is unchanged.
     */
    fun resolve(name: String): OfflinePlayer? {
        exact(name)?.let { return it }
        val prefix = floodgatePrefixProvider()
        if (prefix != null && prefix.isNotEmpty()) {
            exact(prefix + name)?.let { return it }
            if (name.startsWith(prefix)) {
                exact(name.removePrefix(prefix))?.let { return it }
            }
        }
        return null
    }

    private fun exact(name: String): OfflinePlayer? {
        val op = Bukkit.getOfflinePlayer(name)
        return if (op.hasPlayedBefore()) op else null
    }

    private companion object {
        fun floodgatePrefix(): String? = try {
            FloodgateApi.getInstance().playerPrefix
        } catch (_: Throwable) {
            null // Floodgate not installed / not loaded — no prefix fallback
        }
    }
}
