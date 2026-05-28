package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.nexus.annotations.Component
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
        return GuildProvider.GuildRef(guild.id.toString(), guild.name)
    }

    override fun guildById(id: String): GuildProvider.GuildRef? {
        val uuid = try {
            UUID.fromString(id)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val guild = resolvedGuildService.getGuild(uuid) ?: return null
        return GuildProvider.GuildRef(guild.id.toString(), guild.name)
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