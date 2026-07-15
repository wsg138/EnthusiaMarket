package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallState
import java.time.Duration
import java.time.Instant

/** Shared interpretation of configured rent and grace deadlines. */
object RentTimingPolicy {
    private val defaultCollectionInterval: Duration = Duration.ofDays(1)
    private val defaultGracePeriod: Duration = Duration.ofDays(3)

    fun collectionInterval(config: EnthusiaMarketConfig): Duration =
        positiveDuration(config.rent.collectionInterval, defaultCollectionInterval)

    fun gracePeriod(config: EnthusiaMarketConfig): Duration =
        positiveDuration(config.rent.gracePeriod, defaultGracePeriod)

    fun effectiveNextRentAt(stall: Stall, config: EnthusiaMarketConfig): Instant? = when {
        stall.nextRentAt != null -> stall.nextRentAt
        stall.state == StallState.OWNED && stall.ownerSince != null ->
            stall.ownerSince.plus(collectionInterval(config))
        else -> null
    }

    fun graceEndsAt(stall: Stall, config: EnthusiaMarketConfig): Instant? {
        // Mirrors RentCollectionService: GRACE grace window starts at nextRentAt
        // (when rent was actually due), not ownerSince (which is preserved from
        // original purchase and no longer reset on GRACE entry).
        val graceStartedAt = when {
            stall.nextRentAt != null -> stall.nextRentAt
            stall.state == StallState.GRACE && stall.ownerSince != null ->
                stall.ownerSince.plus(collectionInterval(config))
            else -> null
        }
        return graceStartedAt?.plus(gracePeriod(config))
    }

    private fun positiveDuration(raw: String, fallback: Duration): Duration =
        runCatching { Duration.parse(raw) }
            .getOrNull()
            ?.takeIf { !it.isZero && !it.isNegative }
            ?: fallback
}
