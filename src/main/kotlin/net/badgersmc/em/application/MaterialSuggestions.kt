package net.badgersmc.em.application

/**
 * Pure tab-completion helper for `/shop search` (REQ-283). Application layer: stdlib only, no Bukkit —
 * the caller (infrastructure) supplies the item-material names; this just prefix-filters them.
 *
 * Skeleton for TC-1 — filled by `spear:engine` to flip the red test green.
 */
object MaterialSuggestions {

    /**
     * Candidates whose name starts with [typed] (case-insensitive), in input order.
     * A blank [typed] returns every candidate.
     */
    fun matching(candidates: List<String>, typed: String): List<String> =
        if (typed.isBlank()) candidates
        else candidates.filter { it.startsWith(typed, ignoreCase = true) }
}
