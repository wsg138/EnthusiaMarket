package net.badgersmc.em.domain.sign

import net.badgersmc.em.domain.stall.StallId

/**
 * Physical sign in the world bound to a stall (REQ-250).
 *
 * Right-clicking an UNOWNED stall's sign withdraws [price] from the
 * clicker and awards the stall to them. AUCTIONING stalls (during the
 * one-shot mass auction) render an info-only state; OWNED stalls
 * render a "sold" state and ignore clicks.
 *
 * Price is set on creation via the third line of the placed sign;
 * persists alongside the location binding so the same sign always
 * sells the stall at the same price until broken + re-placed.
 *
 * Identified by (world, x, y, z) — primary key in persistence.
 */
data class PurchaseSign(
    val stallId: StallId,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val price: Long,
) {
    init {
        require(price > 0) { "PurchaseSign price must be positive (got $price)" }
    }

    val locationKey: String get() = "$world:$x:$y:$z"
}
