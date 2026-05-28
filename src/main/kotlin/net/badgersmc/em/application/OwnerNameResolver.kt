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
        val template = config.signs.ownerNameTemplate
        return expandPapiIfAvailable(uuid, template, bukkitName)
            ?: template.replace("%player_name%", bukkitName)
    }

    private fun resolveGuild(guildId: String): String {
        val template = config.signs.guildNameTemplate
        val guildName = try {
            guildProvider.guildById(guildId)?.name ?: guildId
        } catch (_: Throwable) {
            guildId
        }
        return template
            .replace("%guild_name%", guildName)
            .replace("%guild_id%", guildId)
    }

    /**
     * Reflectively invoke PlaceholderAPI.setPlaceholders so the plugin
     * doesn't need a hard PAPI dependency. Returns null when PAPI
     * isn't loaded or the call fails — caller falls back to manual
     * placeholder substitution.
     */
    private fun expandPapiIfAvailable(uuid: UUID, template: String, fallbackName: String): String? {
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
            // surface the fallback so the sign still renders.
            template.replace("%player_name%", fallbackName)
        }
    }
}
