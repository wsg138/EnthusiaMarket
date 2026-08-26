package net.badgersmc.em.domain.maintenance

import java.time.Instant

/**
 * Result of [MaintenanceFreezeRepository.unfreeze] — how many timer rows
 * were shifted forward when a maintenance freeze was lifted.
 */
data class FreezeShiftResult(
    /** Stalls whose `next_rent_at` was pushed forward (OWNED/GRACE deadlines). */
    val stalls: Int,
    /** OPEN auctions whose `end_at` was pushed forward (sentinel auctions skipped). */
    val auctions: Int,
)

/**
 * Persistence for the maintenance-freeze flag.
 *
 * Single-row meta table (`id = 1`). The freeze must survive server restarts —
 * the server is typically taken down for the maintenance window and the freeze
 * must still be in effect when it comes back up, so all state lives in the DB.
 */
interface MaintenanceFreezeRepository {
    /** The instant the freeze began, or null when no freeze is active. */
    fun frozenSince(): Instant?

    /** Start a maintenance freeze at [now]. */
    fun begin(now: Instant)

    /**
     * Lift the freeze: atomically shifts every timer column forward by
     * [shiftMs] (stalls.next_rent_at, open auctions.end_at — skipping the
     * `Long.MAX_VALUE` "waiting for first bid" sentinel) and clears the
     * freeze row in the same transaction. Returns how many rows shifted.
     */
    fun unfreeze(shiftMs: Long): FreezeShiftResult
}
