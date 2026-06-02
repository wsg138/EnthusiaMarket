package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Resolves a display name for a stall owner — used by the purchase
 * sign renderer to label OWNED stalls (REQ-250 extension).
 *
 * - **SOLO**: expands `signs.ownerNameTemplate` (default
 *   `%player_name%`) via PlaceholderAPI when loaded. Falls back to
 *   `OfflinePlayer.name` substitution when PAPI isn't present so
 *   the default config still renders something useful.
 * - **GUILD**: expands `signs.guildNameTemplate` (default
 *   `%guild_name%`) against [GuildProvider.guildById]; falls back to
 *   the guild id when the guild can't be resolved.
 * - **NONE**: empty string.
 */
@Service
class OwnerNameResolver(
    private val config: EnthusiaMarketConfig,
    private val guildProvider: GuildProvider,
) {

    fun displayNameFor(owner: OwnerRef): String = when (owner.type) {
        OwnerType.NONE -> ""
        OwnerType.SOLO -> resolveSolo(owner.id)
        OwnerType.GUILD -> resolveGuild(owner.id)
    }

    private fun resolveSolo(uuidStr: String): String {
        val uuid = try {
            UUID.fromString(uuidStr)
        } catch (_: IllegalArgumentException) {
            return uuidStr
        }
        val bukkitName = try {
            Bukkit.getOfflinePlayer(uuid).name ?: uuidStr
        } catch (_: Throwable) {
            // Bukkit may be unavailable (unit tests etc).
            uuidStr
        }
        // Resolve %player_name% from data we already hold (the owner's name),
        // rather than delegating it to PAPI's optional "Player" expansion —
        // that expansion is frequently not installed, in which case PAPI
        // returns the token unparsed and the raw %player_name% lands on the
        // sign. After our substitution, run PAPI only for any *remaining*
        // custom tokens an admin may have added (e.g. %luckperms_prefix%).
        val withName = config.signs.ownerNameTemplate.replace("%player_name%", bukkitName)
        return expandPapiIfAvailable(uuid, withName) ?: withName
    }

    private fun resolveGuild(guildId: String): String {
        val template = config.signs.guildNameTemplate
        val guild = try {
            guildProvider.guildById(guildId)
        } catch (_: Throwable) {
            null
        }
        // Pull tag/emoji from the LumaGuilds API directly — NOT a PAPI guild
        // placeholder, which is player-scoped and can't resolve the owning
        // guild on a sign. The resolved value is MiniMessage-parsed by the
        // lang renderer (Placeholder.parsed), so a MiniMessage-formatted tag
        // renders with colour on the sign.
        return template
            .replace("%guild_name%", guild?.name ?: guildId)
            .replace("%guild_tag%", guild?.tag ?: "")
            .replace("%guild_emoji%", guild?.emoji ?: "")
            .replace("%guild_id%", guildId)
    }

    /**
     * Reflectively invoke PlaceholderAPI.setPlaceholders so the plugin
     * doesn't need a hard PAPI dependency. Returns null when PAPI isn't
     * loaded, the call fails, or there are no tokens left to expand — the
     * caller then keeps its already name-substituted template.
     */
    private fun expandPapiIfAvailable(uuid: UUID, template: String): String? {
        // Nothing left to expand — skip the reflection entirely (the common
        // case once %player_name% has been substituted by the caller).
        if (!template.contains('%')) return null
        val pm = try {
            Bukkit.getPluginManager()
        } catch (_: Throwable) {
            return null
        }
        if (pm == null || !pm.isPluginEnabled("PlaceholderAPI")) return null
        return try {
            val papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
            val offlinePlayer = Bukkit.getOfflinePlayer(uuid)
            val method = papiClass.getMethod(
                "setPlaceholders",
                Class.forName("org.bukkit.OfflinePlayer"),
                String::class.java,
            )
            method.invoke(null, offlinePlayer, template) as? String
        } catch (_: Throwable) {
            // PAPI present but reflection failed (version mismatch etc) —
            // caller keeps the name-substituted template.
            null
        }
    }
}
