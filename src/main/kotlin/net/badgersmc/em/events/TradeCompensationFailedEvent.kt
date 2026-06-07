package net.badgersmc.em.events

import java.util.UUID
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired when a post-settlement compensation step in the trade pipeline
 * fails (e.g. buyer refund, seller proceeds, tax routing) and the system
 * could not self-heal. The settlement state is whatever the originating
 * flow left it in (charge committed, ownership committed, or only
 * partially paid out) — listeners exist purely to notify operators
 * (broadcast to staff, write to an audit channel, open a ticket).
 */
class TradeCompensationFailedEvent(
    val context: String,
    val detail: String,
    val affected: UUID?,
    val amount: Long,
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic val handlerList = HandlerList()
    }
}
