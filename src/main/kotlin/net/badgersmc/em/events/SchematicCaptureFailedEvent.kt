package net.badgersmc.em.events

import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired when a stall schematic capture fails during a claim transition
 * (REQ-274). The ownership transition is aborted and any economy charge is
 * refunded before this event is emitted, so listeners exist purely to notify
 * operators (e.g. broadcast to staff, write to an audit channel).
 */
class SchematicCaptureFailedEvent(
    val stallId: String,
    val world: String,
    val regionId: String,
    val cause: Throwable,
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic val handlerList = HandlerList()
    }
}
