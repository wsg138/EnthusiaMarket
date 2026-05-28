package net.badgersmc.em.events

import net.badgersmc.em.domain.stall.StallState
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired whenever a stall transitions between ownership / lifecycle
 * states (auction settlement, sell-offer purchase, rent eviction,
 * admin reassignment). Listeners can react without polling — used by
 * `PurchaseSignRefreshListener` to re-render bound signs (REQ-252).
 */
class StallStateChangedEvent(
    val stallId: String,
    val previous: StallState,
    val current: StallState,
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic val handlerList = HandlerList()
    }
}
