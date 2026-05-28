package net.badgersmc.em.events

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/** Fired after a linked purchase sign is broken and unbound (REQ-253). */
class PurchaseSignBrokenEvent(
    val stallId: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val breakerUuid: UUID?,
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic val handlerList = HandlerList()
    }
}
