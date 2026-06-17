package net.badgersmc.em.application

import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Service

/**
 * Rewrites every stall's stored rent terms to the current config default (REQ-003 support).
 *
 * Stall rent terms are snapshotted per-stall at import time, so a config change (e.g. switching to
 * flat rent) does NOT reach already-imported stalls. `/em rent resync` calls this to push the current
 * `defaultRent` onto every existing stall. Opt-in, mirrors how `/em import` re-applies region flags.
 *
 * Skeleton for ST-7 — filled by the engine step to flip the red test green.
 */
@Service
class RentTermsResyncService(
    private val stalls: StallRepository,
    private val defaultRent: RentTerms,
) {
    /** Set every stall whose stored terms differ from [defaultRent] to it. Returns the count changed. */
    fun resync(): Int {
        var changed = 0
        for (stall in stalls.all()) {
            if (stall.rentTerms != defaultRent) {
                stalls.save(stall.copy(rentTerms = defaultRent))
                changed++
            }
        }
        return changed
    }
}
