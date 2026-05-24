package net.badgersmc.em.events

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Called after a successful shop transaction has been completed (REQ-027).
 *
 * Used for logging, income routing, and advancement tracking.
 */
class PostShopTransactionEvent(
    val buyer: Player,
    val landlordId: UUID,
    val item: ItemStack,
    val quantity: Int,
    val pricePaid: Double
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}