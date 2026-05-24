package net.badgersmc.em.interaction

import org.bukkit.entity.Player

/**
 * Platform-agnostic menu interface.
 * Java players get IFramework ChestGui; Bedrock players get Cumulus forms.
 */
interface Menu {
    fun open()
}
