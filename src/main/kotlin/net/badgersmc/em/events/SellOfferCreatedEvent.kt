package net.badgersmc.em.events

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/** Fired after a sell offer has been created and persisted (REQ-260). */
class SellOfferCreatedEvent(
    val stallId: String,
    val sellerUuid: UUID,
    val price: Long,
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic val handlerList = HandlerList()
    }
}
