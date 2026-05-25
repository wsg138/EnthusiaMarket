package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.nexus.annotations.Component
import net.lumalyte.lg.application.services.BankService
import net.lumalyte.lg.application.services.GuildService
import net.lumalyte.lg.application.services.MemberService
import net.lumalyte.lg.application.services.RankService
import org.koin.core.context.GlobalContext
import java.util.UUID

/**
 * Real [GuildProvider] backed by the LumaGuilds API.
 *
 * Resolves LumaGuilds services lazily via Koin's [GlobalContext].
 * Optional constructor parameters allow direct injection of mock services in tests
 * without requiring a running Koin context.
 */
@Component
class LumaGuildsGuildProvider(
    private val guildService: GuildService? = null,
    private val memberService: MemberService? = null,
    private val rankService: RankService? = null,
    private val bankService: BankService? = null,
) : GuildProvider {

    private val resolvedGuildService: GuildService by lazy {
        guildService ?: GlobalContext.get().get()
    }
    private val resolvedMemberService: MemberService by lazy {
        memberService ?: GlobalContext.get().get()
    }
    private val resolvedRankService: RankService by lazy {
        rankService ?: GlobalContext.get().get()
    }
    private val resolvedBankService: BankService by lazy {
        bankService ?: GlobalContext.get().get()
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