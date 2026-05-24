package net.badgersmc.em.domain.ports

import java.util.UUID

/**
 * Port for checking and transferring items between players and shop containers.
 */
interface ItemProvider {
    /** Returns true if the player has at least [amount] of [itemKey] in their inventory. */
    fun playerHasItem(player: UUID, itemKey: String, amount: Int): Boolean

    /** Removes [amount] of [itemKey] from the player's inventory. Returns true if successful. */
    fun takeItemFromPlayer(player: UUID, itemKey: String, amount: Int): Boolean

    /** Gives [amount] of [itemKey] to the player's inventory. Returns true if successful. */
    fun giveItemToPlayer(player: UUID, itemKey: String, amount: Int): Boolean
}