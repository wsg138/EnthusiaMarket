package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.nexus.annotations.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.lumalyte.lg.application.services.BankService
import net.lumalyte.lg.application.services.GuildService
import net.lumalyte.lg.application.services.MemberService
import net.lumalyte.lg.application.services.RankService
import net.lumalyte.lg.domain.entities.RankPermission
import org.koin.core.context.GlobalContext
import java.util.UUID

/**
 * Real [GuildProvider] backed by the LumaGuilds API.
 *
 * Resolves LumaGuilds services lazily via Koin's [GlobalContext].
 */
@Component
class LumaGuildsGuildProvider : GuildProvider {

    private val resolvedGuildService: GuildService by lazy {
        GlobalContext.get().get()
    }
    private val resolvedMemberService: MemberService by lazy {
        GlobalContext.get().get()
    }
    private val resolvedRankService: RankService by lazy {
        GlobalContext.get().get()
    }
    private val resolvedBankService: BankService by lazy {
        GlobalContext.get().get()
    }

    private val dissolveHandlers = mutableListOf<(String) -> Unit>()

    override fun guildOf(player: UUID): GuildProvider.GuildRef? {
        val guildIds = resolvedMemberService.getPlayerGuilds(player)
        val firstId = guildIds.firstOrNull() ?: return null
        val guild = resolvedGuildService.getGuild(firstId) ?: return null
        return GuildProvider.GuildRef(
            guild.id.toString(),
            guild.name,
            normalizeToMiniMessage(guild.tag),
            normalizeToMiniMessage(guild.emoji),
        )
    }

    override fun guildById(id: String): GuildProvider.GuildRef? {
        val uuid = try {
            UUID.fromString(id)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val guild = resolvedGuildService.getGuild(uuid) ?: return null
        return GuildProvider.GuildRef(
            guild.id.toString(),
            guild.name,
            normalizeToMiniMessage(guild.tag),
            normalizeToMiniMessage(guild.emoji),
        )
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
        val uuid = try {
            UUID.fromString(guildId)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return resolvedMemberService.getMember(player, uuid) != null
    }

    override fun hasShopPermission(player: UUID, guildId: String, permission: GuildProvider.GuildPermission): Boolean {
        val guildUuid = try {
            UUID.fromString(guildId)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val lgPermission = permission.toRankPermission() ?: return false
        return try {
            resolvedMemberService.hasPermission(player, guildUuid, lgPermission)
        } catch (_: Exception) {
            false
        }
    }

    @Deprecated("Use hasShopPermission with GuildPermission", replaceWith = ReplaceWith("hasShopPermission(player, guildId, permission)"))
    override fun hasPermission(player: UUID, guildId: String, node: String): Boolean {
        val guildUuid = try {
            UUID.fromString(guildId)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val playerRankId = resolvedMemberService.getPlayerRankId(player, guildUuid) ?: return false
        val ranks = resolvedRankService.listRanks(guildUuid)
        val playerRank = ranks.find { it.id == playerRankId } ?: return false
        val targetRank = ranks.find { it.name.equals(node, ignoreCase = true) } ?: return false
        // Lower priority number = higher rank (0 = Owner)
        return playerRank.priority <= targetRank.priority
    }

    override fun bankBalance(guildId: String): Long {
        val uuid = try {
            UUID.fromString(guildId)
        } catch (_: IllegalArgumentException) {
            return 0L
        }
        return resolvedBankService.getBalance(uuid).toLong()
    }

    override fun bankWithdraw(guildId: String, amount: Long): Boolean {
        if (amount <= 0 || amount > Int.MAX_VALUE) return false
        val uuid = try {
            UUID.fromString(guildId)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return resolvedBankService.withdraw(uuid, SYSTEM_ACTOR_UUID, amount.toInt(), "System withdrawal") != null
    }

    override fun bankDeposit(guildId: String, amount: Long): Boolean {
        if (amount <= 0 || amount > Int.MAX_VALUE) return false
        val uuid = try {
            UUID.fromString(guildId)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return resolvedBankService.deposit(uuid, SYSTEM_ACTOR_UUID, amount.toInt(), "System deposit") != null
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