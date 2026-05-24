package net.badgersmc.em.infrastructure.bedrock

import net.badgersmc.nexus.annotations.Component
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Dispatches UI interaction for Bedrock (Floodgate) vs Java players.
 *
 * For Bedrock players, Cumulus forms are dispatched instead of Bukkit GUI.
 * Uses reflection to check FloodgateApi to avoid hard runtime dependency
 * when Floodgate is not installed on the server.
 */
@Component
class UiDispatcher {

    /**
     * Returns true if the given UUID belongs to a Bedrock/Floodgate player.
     * Uses reflection to access FloodgateApi so the plugin works gracefully
     * when Floodgate is absent.
     */
    fun isBedrockPlayer(uuid: UUID): Boolean {
        return try {
            val floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
            val getInstance = floodgateApiClass.getMethod("getInstance")
            val floodgateApi = getInstance.invoke(null)
            val getPlayer = floodgateApiClass.getMethod("getPlayer", UUID::class.java)
            getPlayer.invoke(floodgateApi, uuid) != null
        } catch (_: Exception) {
            // Floodgate not installed or reflection failed — assume Java player
            false
        }
    }

    /**
     * Dispatch the appropriate UI for the player type.
     *
     * - Bedrock (Floodgate) players: Cumulus form (TODO: TDD-43)
     * - Java players: Bukkit inventory GUI (TODO: post TDD-43)
     */
    fun dispatch(player: Player) {
        if (isBedrockPlayer(player.uniqueId)) {
            // TODO: TDD-43 — send Cumulus form
        } else {
            // TODO: Java — send Bukkit inventory GUI
        }
    }
}