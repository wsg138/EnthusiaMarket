package net.badgersmc.em.application

import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.GuildTradePolicyRepository
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.events.GuildTradePolicyChangedEvent
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import java.util.UUID
import java.util.logging.Logger

/**
 * Resolves a guild's trade stance toward a buyer, and lets MANAGE_SHOPS members
 * edit policies. Solo buyers and same-guild buyers are always exempt.
 */
@Service
class GuildTradePolicyService(
    private val policies: GuildTradePolicyRepository,
    private val guildProvider: GuildProvider,
) {
    private val log = Logger.getLogger(GuildTradePolicyService::class.java.name)

    sealed interface TradeStance {
        /** Trade proceeds; multiply the shop cost by [factor]. */
        data class Allowed(val factor: Double) : TradeStance
        /** The buyer's guild is embargoed; reject the trade. */
        data object Embargoed : TradeStance
    }

    fun stanceFor(ownerGuildId: String, buyer: UUID, direction: SignDirection): TradeStance {
        val buyerGuild = guildProvider.guildOf(buyer)?.id
        if (buyerGuild == null || buyerGuild == ownerGuildId) return TradeStance.Allowed(1.0)
        val policy = policies.find(ownerGuildId, buyerGuild) ?: return TradeStance.Allowed(1.0)
        return when (policy.kind) {
            PolicyKind.EMBARGO -> TradeStance.Embargoed
            PolicyKind.TARIFF -> TradeStance.Allowed(factorFor(direction, policy.ratePct))
        }
    }

    private fun factorFor(direction: SignDirection, ratePct: Int): Double = when (direction) {
        SignDirection.SELL -> 1.0 + ratePct / 100.0
        SignDirection.BUY -> (1.0 - ratePct / 100.0).coerceAtLeast(0.0)
        SignDirection.TRADE -> 1.0
    }

    sealed interface PolicyResult {
        data object Ok : PolicyResult
        data object Denied : PolicyResult            // actor lacks MANAGE_SHOPS
        data class Invalid(val reason: String) : PolicyResult
    }

    fun setTariff(actor: UUID, ownerGuildId: String, targetGuildId: String, ratePct: Int): PolicyResult =
        mutate(actor, ownerGuildId, targetGuildId) {
            // Tariffs cap below 100%: at 100%+ a BUY shop would pay the outsider nothing
            // (confiscation). To block a guild entirely, use an embargo instead.
            if (ratePct !in 0..MAX_TARIFF_PCT)
                return@mutate PolicyResult.Invalid("tariff must be 0..$MAX_TARIFF_PCT — use an embargo to block a guild entirely")
            policies.upsert(GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.TARIFF, ratePct))
            fireChanged(ownerGuildId, targetGuildId, PolicyKind.TARIFF, ratePct, GuildTradePolicyChangedEvent.Action.SET)
            PolicyResult.Ok
        }

    fun setEmbargo(actor: UUID, ownerGuildId: String, targetGuildId: String): PolicyResult =
        mutate(actor, ownerGuildId, targetGuildId) {
            policies.upsert(GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.EMBARGO, 0))
            fireChanged(ownerGuildId, targetGuildId, PolicyKind.EMBARGO, 0, GuildTradePolicyChangedEvent.Action.SET)
            PolicyResult.Ok
        }

    fun clear(actor: UUID, ownerGuildId: String, targetGuildId: String): PolicyResult =
        mutate(actor, ownerGuildId, targetGuildId) {
            policies.delete(ownerGuildId, targetGuildId)
            fireChanged(ownerGuildId, targetGuildId, null, 0, GuildTradePolicyChangedEvent.Action.CLEARED)
            PolicyResult.Ok
        }

    fun list(ownerGuildId: String): List<GuildTradePolicy> = policies.listByOwner(ownerGuildId)

    /** A guild's policy toward [buyer]'s guild, ignoring trade direction. Null for solo / own-guild / no policy. */
    fun policyToward(ownerGuildId: String, buyer: UUID): GuildTradePolicy? {
        val buyerGuild = guildProvider.guildOf(buyer)?.id ?: return null
        if (buyerGuild == ownerGuildId) return null
        return policies.find(ownerGuildId, buyerGuild)
    }

    private fun fireChanged(owner: String, target: String, kind: PolicyKind?, rate: Int, action: GuildTradePolicyChangedEvent.Action) {
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                GuildTradePolicyChangedEvent(owner, target, kind, rate, action)
            )
        } catch (e: Exception) {
            log.warning("GuildTradePolicyService: failed to fire policy-changed event: ${e.message}")
        }
    }

    private inline fun mutate(
        actor: UUID, ownerGuildId: String, targetGuildId: String, action: () -> PolicyResult
    ): PolicyResult {
        if (ownerGuildId == targetGuildId) return PolicyResult.Invalid("A guild cannot set a policy on itself")
        if (!guildProvider.hasShopPermission(actor, ownerGuildId, GuildProvider.GuildPermission.MANAGE_SHOPS))
            return PolicyResult.Denied
        return action()
    }

    companion object {
        /** Max tariff a guild may set. Caps below 100% so a BUY shop never pays 0 (confiscation). */
        const val MAX_TARIFF_PCT = 99
    }
}
