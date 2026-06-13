package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.nexus.annotations.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.lumalyte.lg.api.GuildLookup
import net.lumalyte.lg.api.GuildSummary
import net.lumalyte.lg.domain.entities.RankPermission
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Real [GuildProvider] backed by LumaGuilds' [GuildLookup] API.
 *
 * LumaGuilds registers [GuildLookup] in Bukkit's ServicesManager at enable, so we
 * load it via [org.bukkit.plugin.ServicesManager] rather than reaching into
 * LumaGuilds' Koin container — a cross-classloader Koin `get<T>()` LinkageErrors
 * on `kotlin.reflect.KClass`. The lookup is resolved lazily (LumaGuilds loads
 * BEFORE EnthusiaMarket, so it is registered by the time any command runs) and is
 * null only if LumaGuilds is absent; every method degrades to a safe default then.
 */
@Component
class LumaGuildsGuildProvider : GuildProvider {

    private val lookup: GuildLookup? by lazy {
        Bukkit.getServicesManager().load(GuildLookup::class.java)
    }

    private val dissolveHandlers = mutableListOf<(String) -> Unit>()

    override fun guildOf(player: UUID): GuildProvider.GuildRef? {
        val lg = lookup ?: return null
        val firstId = lg.getPlayerGuildIds(player).firstOrNull() ?: return null
        val guild = lg.getGuild(firstId) ?: return null
        return toGuildRef(guild)
    }

    override fun guildById(id: String): GuildProvider.GuildRef? {
        val uuid = parseUuid(id) ?: return null
        val guild = lookup?.getGuild(uuid) ?: return null
        return toGuildRef(guild)
    }

    override fun listGuilds(): List<GuildProvider.GuildRef> =
        lookup?.getAllGuilds()?.map { toGuildRef(it) } ?: emptyList()

    /** Map a LumaGuilds [GuildSummary] to [GuildProvider.GuildRef], normalising tag/emoji to MiniMessage. */
    private fun toGuildRef(guild: GuildSummary): GuildProvider.GuildRef =
        GuildProvider.GuildRef(
            guild.id.toString(),
            guild.name,
            normalizeToMiniMessage(guild.tag),
            normalizeToMiniMessage(guild.emoji),
        )

    private fun parseUuid(value: String): UUID? =
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * Normalise a stored guild tag/emoji to MiniMessage so EM's sign renderer
     * (which parses placeholder values as MiniMessage) colours it correctly.
     *
     * LumaGuilds stores tags in whatever format the setter passed: the Java
     * tag-editor menu converts legacy `&` codes to MiniMessage on save, but the
     * `/guild tag` command and the Bedrock editor store the raw input — so
     * legacy-coded tags exist in the wild. Mirror LumaGuilds' own
     * ColorCodeUtils.convertLegacyToMiniMessage: pass MiniMessage through
     * unchanged, convert legacy `&` codes, and never throw.
     */
    private fun normalizeToMiniMessage(raw: String?): String {
        val value = raw ?: return ""
        if (value.isEmpty()) return ""
        // Already MiniMessage (contains a tag) — leave it alone.
        if (value.contains(MINI_TAG)) return value
        // No legacy codes either — plain text, nothing to convert.
        if (!value.contains('&') && !value.contains('§')) return value
        return try {
            val legacy = if (value.contains('§')) {
                LegacyComponentSerializer.legacySection()
            } else {
                LegacyComponentSerializer.legacyAmpersand()
            }
            MiniMessage.miniMessage().serialize(legacy.deserialize(value))
        } catch (_: Exception) {
            value
        }
    }


    override fun isMember(player: UUID, guildId: String): Boolean {
        val uuid = parseUuid(guildId) ?: return false
        return lookup?.isMember(player, uuid) ?: false
    }

    override fun hasShopPermission(player: UUID, guildId: String, permission: GuildProvider.GuildPermission): Boolean {
        val guildUuid = parseUuid(guildId) ?: return false
        val lgPermission = permission.toRankPermission() ?: return false
        return try {
            lookup?.hasShopPermission(player, guildUuid, lgPermission.name) ?: false
        } catch (_: Exception) {
            false
        }
    }

    @Deprecated("Use hasShopPermission with GuildPermission", replaceWith = ReplaceWith("hasShopPermission(player, guildId, permission)"))
    override fun hasPermission(player: UUID, guildId: String, node: String): Boolean {
        val guildUuid = parseUuid(guildId) ?: return false
        return lookup?.hasRankAtLeast(player, guildUuid, node) ?: false
    }

    override fun bankBalance(guildId: String): Long {
        val uuid = parseUuid(guildId) ?: return 0L
        return lookup?.getBankBalance(uuid) ?: 0L
    }

    override fun bankWithdraw(guildId: String, amount: Long): Boolean {
        if (amount <= 0 || amount > Int.MAX_VALUE) return false
        val uuid = parseUuid(guildId) ?: return false
        return lookup?.bankWithdraw(uuid, SYSTEM_ACTOR_UUID, amount.toInt(), "System withdrawal") ?: false
    }

    override fun bankDeposit(guildId: String, amount: Long): Boolean {
        if (amount <= 0 || amount > Int.MAX_VALUE) return false
        val uuid = parseUuid(guildId) ?: return false
        return lookup?.bankDeposit(uuid, SYSTEM_ACTOR_UUID, amount.toInt(), "System deposit") ?: false
    }

    override fun onDissolved(handler: (String) -> Unit) {
        dissolveHandlers.add(handler)
    }

    /**
     * Called by [GuildDisbandedEventListener] when a [net.lumalyte.lg.domain.events.GuildDisbandedEvent]
     * is fired. Notifies all registered dissolve handlers.
     */
    internal fun handleDisbanded(guildId: String) {
        dissolveHandlers.toList().forEach { handler ->
            try {
                handler(guildId)
            } catch (e: Exception) {
                // Isolate handler failures — one failing handler shouldn't prevent others
            }
        }
    }

    companion object {
        private val SYSTEM_ACTOR_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
        private val MINI_TAG = Regex("<[^>]+>")
    }
}

/**
 * Maps EM's [GuildProvider.GuildPermission] to LumaGuilds [RankPermission].
 * Returns null if no direct mapping exists.
 */
private fun GuildProvider.GuildPermission.toRankPermission(): RankPermission? = when (this) {
    GuildProvider.GuildPermission.MANAGE_SHOPS -> RankPermission.EDIT_SHOP_STOCK
    GuildProvider.GuildPermission.ACCESS_SHOP_CHESTS -> RankPermission.ACCESS_SHOP_CHESTS
    GuildProvider.GuildPermission.EDIT_SHOP_STOCK -> RankPermission.EDIT_SHOP_STOCK
    GuildProvider.GuildPermission.MODIFY_SHOP_PRICES -> RankPermission.MODIFY_SHOP_PRICES
}