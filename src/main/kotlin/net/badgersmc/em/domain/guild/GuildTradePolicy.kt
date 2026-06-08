package net.badgersmc.em.domain.guild

/** How a guild treats another guild's members at its shops. */
enum class PolicyKind { TARIFF, EMBARGO }

/**
 * One guild's trade stance toward another guild. [ratePct] is the tariff
 * percentage (0..1000) and is ignored for [PolicyKind.EMBARGO].
 */
data class GuildTradePolicy(
    val ownerGuildId: String,
    val targetGuildId: String,
    val kind: PolicyKind,
    val ratePct: Int,
) {
    init {
        require(ownerGuildId != targetGuildId) { "A guild cannot set a trade policy on itself" }
        require(ratePct in 0..MAX_RATE_PCT) { "ratePct must be in 0..$MAX_RATE_PCT" }
    }
    companion object { const val MAX_RATE_PCT = 1000 }
}
