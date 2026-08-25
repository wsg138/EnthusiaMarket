package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.nexus.annotations.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/**
 * Unfreezes a stall's shops whenever the stall leaves a rent-penalty state.
 *
 * Shops are frozen as a rent-enforcement penalty: `RentCollectionService`
 * bulk-freezes every shop on a stall when it enters GRACE or
 * EMERGENCY_AUCTIONING (freeze-first, then save — see pitfall: freeze before
 * saving the stall or the grace period runs with active shops). But the
 * recovery paths — rent payment via
 * [net.badgersmc.em.application.StallRentExtensionService.extend], auction
 * settlement (`AuctionLifecycleService.settleWithWinner`), stall buyout
 * (`StallBuyoutService`), sell-offer purchase — only updated the STALL row;
 * they never unfroze the shops.
 *
 * Result (stall39 report, 2026-08-01): a stall showing a healthy rent
 * countdown on its purchase sign while every shop silently rejected trades
 * with "frozen". The stall state and the shop-freeze flag had drifted apart.
 *
 * This listener unfreezes shops when a stall transitions onto a non-penalty
 * state (OWNED / UNOWNED) from any state other than OWNED (REQ-302):
 *   GRACE / EMERGENCY_AUCTIONING → OWNED   (rent paid, auction won)
 *   GRACE / EMERGENCY_AUCTIONING → UNOWNED (revert, eviction, no-bid)
 *   AUCTIONING / RE_AUCTIONING → OWNED/UNOWNED (force-auction settle/revert)
 *   UNOWNED → OWNED                        (buyout of a stall with leftover
 *                                           frozen shops from a pre-fix revert)
 *
 * Penalty → penalty (GRACE → EMERGENCY_AUCTIONING) keeps shops frozen.
 * Deliberately NOT unfrozen: OWNED → OWNED (rent extension re-fire). An owner
 * may have manually frozen their own shop via /shop edit — a same-state event
 * must not clobber that. Freezing stays exclusively in `RentCollectionService`
 * where the freeze-before-save ordering matters.
 *
 * Idempotent — `freezeByStall` is a bulk UPDATE that no-ops when the flag
 * already matches.
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class ShopFreezeStateListener(
    private val shops: ShopRepository,
) : Listener {

    @EventHandler
    fun onStallStateChanged(event: StallStateChangedEvent) {
        if (!shouldUnfreeze(event.previous, event.current)) return
        shops.freezeByStall(event.stallId, frozen = false)
    }

    /**
     * Shops are unfrozen when a stall lands on a non-penalty state
     * (OWNED / UNOWNED) from any state other than OWNED — REQ-302.
     * Source-agnostic on purpose: GRACE/EMERGENCY_AUCTIONING (rent paid,
     * auction won, revert), AUCTIONING/RE_AUCTIONING (admin force-auction
     * settling or reverting), and UNOWNED (buyout) all must clear the
     * rent-penalty freeze. OWNED → OWNED is excluded so a manual `/shop
     * edit` freeze survives a rent-extension re-fire.
     */
    private fun shouldUnfreeze(previous: StallState, current: StallState): Boolean {
        if (current != StallState.OWNED && current != StallState.UNOWNED) return false
        return previous != StallState.OWNED
    }
}
