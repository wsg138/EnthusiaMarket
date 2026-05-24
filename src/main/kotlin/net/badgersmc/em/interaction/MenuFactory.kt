package net.badgersmc.em.interaction

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.infrastructure.bedrock.PlatformDetectionService
import net.badgersmc.nexus.annotations.Component
import org.bukkit.entity.Player
import java.util.logging.Logger

/**
 * Factory for creating platform-specific menu implementations.
 * Routes Java players to IFramework GUIs and Bedrock players to Cumulus forms.
 */
@Component
class MenuFactory(
    private val platformDetectionService: PlatformDetectionService,
    private val config: EnthusiaMarketConfig,
    private val logger: Logger
) {
    fun shouldUseBedrockMenus(player: Player): Boolean {
        return try {
            if (!config.bedrock.forceForms && !platformDetectionService.isBedrockPlayer(player)) {
                return false
            }
            if (!platformDetectionService.isCumulusAvailable()) {
                logger.fine("Cumulus not available for ${player.name}, using Java menu")
                return false
            }
            true
        } catch (e: Exception) {
            logger.warning("Error detecting Bedrock for ${player.name}: ${e.message}")
            false
        }
    }
}
