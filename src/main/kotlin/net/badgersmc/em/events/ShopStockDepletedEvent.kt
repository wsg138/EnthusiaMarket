package net.badgersmc.em.events

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Called when a linked shop container's stock reaches zero (REQ-026).
 */
class ShopStockDepletedEvent(
    val ownerId: UUID
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}