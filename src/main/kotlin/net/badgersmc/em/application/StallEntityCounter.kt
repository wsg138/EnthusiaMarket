package net.badgersmc.em.application

import net.badgersmc.nexus.annotations.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Hybrid per-stall entity counter (spec §3 Workstream B). Fast path is an
 * in-memory cache mutated by spawn/death events. At the cap boundary the
 * caller supplies an authoritative [rescan] (a live world scan) so a drifted
 * cache never produces a false cancel. Cache keys are stall id strings;
 * type keys are lower-case EntityType names.
 */
@Component
class StallEntityCounter {

    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    fun increment(stallId: String, type: String) {
        val byType = cache.computeIfAbsent(stallId) { ConcurrentHashMap() }
        byType.merge(type, 1, Int::plus)
    }

    fun decrement(stallId: String, type: String) {
        val byType = cache[stallId] ?: return
        byType.compute(type) { _, current -> ((current ?: 0) - 1).coerceAtLeast(0) }
    }

    fun cachedCount(stallId: String, type: String): Int = cache[stallId]?.get(type) ?: 0

    fun cachedTotal(stallId: String): Int = cache[stallId]?.values?.sum() ?: 0

    /** Replace the cached counts for [stallId] with an authoritative scan. */
    fun recount(stallId: String, counts: Map<String, Int>) {
        cache[stallId] = ConcurrentHashMap(counts)
    }

    /**
     * True when adding one more [type] would exceed [cap]. Unlimited (cap<0)
     * never exceeds. At/over the cap by cache, run [rescan] for the stall to
     * get authoritative counts, refresh the cache, and re-check.
     */
    fun wouldExceedTypeCap(
        stallId: String,
        type: String,
        cap: Int,
        rescan: (String) -> Map<String, Int>,
    ): Boolean {
        if (cap < 0) return false
        if (cachedCount(stallId, type) < cap) return false
        // Boundary: confirm with an authoritative scan before refusing.
        val authoritative = rescan(stallId)
        recount(stallId, authoritative)
        return cachedCount(stallId, type) >= cap
    }

    /**
     * True when adding one more entity would exceed the total [cap].
     * Unlimited (cap<0) never exceeds. Mirrors [wouldExceedTypeCap] using the
     * stall total.
     */
    fun wouldExceedTotal(
        stallId: String,
        cap: Int,
        rescan: (String) -> Map<String, Int>,
    ): Boolean {
        if (cap < 0) return false
        if (cachedTotal(stallId) < cap) return false
        val authoritative = rescan(stallId)
        recount(stallId, authoritative)
        return cachedTotal(stallId) >= cap
    }
}
