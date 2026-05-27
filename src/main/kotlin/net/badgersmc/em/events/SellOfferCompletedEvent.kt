package net.badgersmc.em.events

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/** Fired after a sell offer has been accepted and ownership transferred (REQ-261). */
class SellOfferCompletedEvent(
    val stallId: String,
    val sellerUuid: UUID,
    val buyerUuid: UUID,
    val price: Long,
    val tax: Long,
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic val handlerList = HandlerList()
    }
}
