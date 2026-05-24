package net.badgersmc.em.infrastructure.bedrock

import net.badgersmc.nexus.annotations.Component
import org.bukkit.entity.Player
import org.geysermc.floodgate.api.FloodgateApi

/**
 * Detects whether a player is on Bedrock edition via Floodgate API.
 */
@Component
class PlatformDetectionService {

    fun isBedrockPlayer(player: Player): Boolean {
        return try {
            FloodgateApi.getInstance().isFloodgatePlayer(player.uniqueId)
        } catch (_: Exception) {
            false
        }
    }

    fun isCumulusAvailable(): Boolean {
        return try {
            Class.forName("org.geysermc.cumulus.form.Form")
            true
        } catch (_: Exception) {
            false
        }
    }
}
