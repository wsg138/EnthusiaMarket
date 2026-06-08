package net.badgersmc.em.application

import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.GuildTradePolicyRepository
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.annotations.Service
import java.util.UUID

/**
 * Resolves a guild's trade stance toward a buyer, and (Task 4) lets MANAGE_SHOPS
 * members edit policies. Solo buyers and same-guild buyers are always exempt.
 */
@Service
class GuildTradePolicyService(
    private val policies: GuildTradePolicyRepository,
    private val guildProvider: GuildProvider,
) {
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
            if (ratePct !in 0..GuildTradePolicy.MAX_RATE_PCT)
                return@mutate PolicyResult.Invalid("rate must be 0..${GuildTradePolicy.MAX_RATE_PCT}")
            policies.upsert(GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.TARIFF, ratePct))
            PolicyResult.Ok
        }

    fun setEmbargo(actor: UUID, ownerGuildId: String, targetGuildId: String): PolicyResult =
        mutate(actor, ownerGuildId, targetGuildId) {
            policies.upsert(GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.EMBARGO, 0))
            PolicyResult.Ok
        }

    fun clear(actor: UUID, ownerGuildId: String, targetGuildId: String): PolicyResult =
        mutate(actor, ownerGuildId, targetGuildId) {
            policies.delete(ownerGuildId, targetGuildId); PolicyResult.Ok
        }

    fun list(ownerGuildId: String): List<GuildTradePolicy> = policies.listByOwner(ownerGuildId)

    private inline fun mutate(
        actor: UUID, ownerGuildId: String, targetGuildId: String, action: () -> PolicyResult
    ): PolicyResult {
        if (ownerGuildId == targetGuildId) return PolicyResult.Invalid("A guild cannot set a policy on itself")
        if (!guildProvider.hasShopPermission(actor, ownerGuildId, GuildProvider.GuildPermission.MANAGE_SHOPS))
            return PolicyResult.Denied
        return action()
    }
}
