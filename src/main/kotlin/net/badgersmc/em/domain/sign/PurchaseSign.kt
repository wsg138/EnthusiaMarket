package net.badgersmc.em.domain.sign

import net.badgersmc.em.domain.stall.StallId

/**
 * Physical sign in the world bound to a stall (REQ-250). Right-clicks
 * are routed through the appropriate flow based on [kind]; the sign's
 * lines are re-rendered from a lang template on every state change
 * (REQ-252).
 *
 * Identified by (world, x, y, z) — the primary key in persistence.
 */
data class PurchaseSign(
    val stallId: StallId,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val kind: PurchaseSignKind,
) {
    /** Stable key for repository lookup. */
    val locationKey: String get() = "$world:$x:$y:$z"
}
