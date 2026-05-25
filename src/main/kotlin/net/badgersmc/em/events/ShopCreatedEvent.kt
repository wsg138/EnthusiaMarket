package net.badgersmc.em.events

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Called after a new shop has been created and persisted (REQ-026).
 */
class ShopCreatedEvent(
    val ownerId: UUID
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}