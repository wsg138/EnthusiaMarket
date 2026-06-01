package net.badgersmc.em.domain.stall

/**
 * Per-region-kind entity cap (REQ-220). [total] caps the sum of all capped
 * entities (`_total` in entitylimits.yml); [perType] caps individual
 * Bukkit EntityType names (lower-cased). A cap of -1 means unlimited.
 * Per-stall overrides merge additively via [mergeExtras] (REQ-222).
 */
data class EntityLimitGroup(
    val total: Int,
    val perType: Map<String, Int>,
) {
    /** Per-type cap for [type] (lower-case EntityType name); -1 if unlisted/unlimited. */
    fun capFor(type: String): Int = perType[type] ?: -1

    /** True when [currentCount] of [type] is at or above its cap (unlimited never over). */
    fun isOverTypeCap(type: String, currentCount: Int): Boolean {
        val cap = capFor(type)
        return cap >= 0 && currentCount >= cap
    }

    /** True when [currentTotal] capped entities is at or above [total] (unlimited never over). */
    fun isOverTotal(currentTotal: Int): Boolean = total >= 0 && currentTotal >= total

    /**
     * Merge per-stall extra allowance on top of the base caps. Extras add to
     * both the total and each per-type cap. Unlimited (-1) base caps stay
     * unlimited. See REQ-222.
     */
    fun mergeExtras(extraTotal: Int, extraPerType: Map<String, Int>): EntityLimitGroup {
        val mergedTotal = if (total < 0) -1 else total + extraTotal
        val mergedPerType = perType.toMutableMap()
        for ((type, extra) in extraPerType) {
            val base = perType[type]
            mergedPerType[type] = when {
                base == null -> extra
                base < 0 -> -1
                else -> base + extra
            }
        }
        return EntityLimitGroup(mergedTotal, mergedPerType)
    }
}
