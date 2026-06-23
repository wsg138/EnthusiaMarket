package net.badgersmc.em.application

import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Service

/**
 * Rewrites every stall's stored rent terms to the current config default (REQ-003 support).
 *
 * Stall rent terms are snapshotted per-stall at import time, so a config change (e.g. switching to
 * flat rent) does NOT reach already-imported stalls. `/em rent resync` calls this to push the current
 * default rent onto every existing stall. Opt-in, mirrors how `/em import` re-applies region flags.
 *
 * Uses [DefaultRentTermsProvider] so the value reflects live config after `/em reload` (fixes #64).
 */
@Service
class RentTermsResyncService(
    private val stalls: StallRepository,
    private val rentTermsProvider: DefaultRentTermsProvider,
) {
    private val log = java.util.logging.Logger.getLogger(RentTermsResyncService::class.java.name)

    /** Set every stall whose stored terms differ from the live config default to it.
     *  Returns the count changed. Logs progress every 50 stalls. */
    fun resync(): Int {
        val currentRent = rentTermsProvider.current()
        val all = stalls.all()
        if (all.isEmpty()) {
            log.info("Rent resync: 0/0 stalls processed")
            return 0
        }
        var changed = 0
        for ((i, stall) in all.withIndex()) {
            if (stall.rentTerms != currentRent) {
                stalls.save(stall.copy(rentTerms = currentRent))
                changed++
            }
            if ((i + 1) % 50 == 0 || i == all.size - 1) {
                log.info("Rent resync: $changed/${i + 1} stalls processed")
            }
        }
        return changed
    }
}
