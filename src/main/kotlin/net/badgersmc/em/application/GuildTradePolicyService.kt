package net.badgersmc.em.application

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
}
