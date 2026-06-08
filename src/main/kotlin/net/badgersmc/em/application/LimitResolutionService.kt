package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.PermissionChecker
import net.badgersmc.nexus.annotations.Service
import java.util.UUID

/**
 * Resolves ARM-style ownership limits for a player by walking every
 * configured limit group whose permission node the player holds and
 * merging them with a "best wins per dimension" rule (REQ-211). `-1`
 * in any group's value means unlimited (REQ-210) and dominates every
 * finite cap. The bypass permission [BYPASS_NODE] short-circuits to
 * unlimited limits (REQ-213).
 *
 * The service is purely a policy boundary — it never reads or mutates
 * stall ownership. Counting the player's current stalls is the
 * caller's responsibility; pass the counts into [canClaim].
 */
@Service
class LimitResolutionService(
    private val config: EnthusiaMarketConfig,
    private val perms: PermissionChecker,
) {

    /** The Bukkit permission node that grants unconditional bypass. */
    private val bypassNode: String = BYPASS_NODE

    /**
     * Per-dimension cap snapshot. [total] is the ceiling across all
     * region kinds; [regionkinds] holds per-kind ceilings. `-1` is
     * the unlimited sentinel inherited from config (REQ-210).
     */
    data class EffectiveLimits(
        val total: Int,
        val regionkinds: Map<String, Int>,
    ) {
        val isUnlimited: Boolean get() = total < 0
    }

    sealed interface ClaimDecision {
        data object Allowed : ClaimDecision
        sealed interface Rejected : ClaimDecision {
            val cap: Int
            data class TotalCapReached(override val cap: Int) : Rejected
            data class KindCapReached(val kind: String, override val cap: Int) : Rejected
        }
    }

    fun effectiveLimits(player: UUID): EffectiveLimits {
        if (perms.has(player, bypassNode)) {
            return EffectiveLimits(total = UNLIMITED, regionkinds = emptyMap())
        }
        var matched = false
        var total = 0
        val regionkinds = mutableMapOf<String, Int>()
        for ((name, group) in config.limits) {
            if (!perms.has(player, "$LIMIT_PREFIX$name")) continue
            matched = true
            total = mergeBest(total, group.total)
            for ((kind, cap) in group.regionkinds) {
                regionkinds.merge(kind, cap, ::mergeBest)
            }
        }
        // No configured group applies to this player → no cap (limits only bind explicitly-grouped players).
        if (!matched) return EffectiveLimits(total = UNLIMITED, regionkinds = emptyMap())
        return EffectiveLimits(total, regionkinds.toMap())
    }

    /**
     * Decide whether [player] may claim a stall of [kind] given their
     * current ownership counts. [currentTotal] is the player's stall
     * count across all kinds; [currentForKind] is the count restricted
     * to [kind].
     *
     * Admin bypass short-circuits to [ClaimDecision.Allowed].
     */
    fun canClaim(
        player: UUID,
        kind: String,
        currentTotal: Int,
        currentForKind: Int,
    ): ClaimDecision {
        val limits = effectiveLimits(player)

        // Total cap check.
        if (limits.total >= 0 && currentTotal >= limits.total) {
            return ClaimDecision.Rejected.TotalCapReached(limits.total)
        }
        // Kind-specific cap check.
        val kindCap = limits.regionkinds[kind]
        if (kindCap != null && kindCap >= 0 && currentForKind >= kindCap) {
            return ClaimDecision.Rejected.KindCapReached(kind, kindCap)
        }
        return ClaimDecision.Allowed
    }

    /**
     * Merge two caps using "best wins": -1 (unlimited) dominates
     * everything, otherwise the larger finite value wins.
     */
    private fun mergeBest(a: Int, b: Int): Int = when {
        a == UNLIMITED || b == UNLIMITED -> UNLIMITED
        else -> maxOf(a, b)
    }

    companion object {
        const val LIMIT_PREFIX = "enthusiamarket.limit."
        const val BYPASS_NODE = "enthusiamarket.admin.bypasslimit"
        const val UNLIMITED = -1
    }
}
