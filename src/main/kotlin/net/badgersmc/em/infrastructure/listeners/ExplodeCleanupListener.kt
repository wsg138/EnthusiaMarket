package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.events.ShopDeletedEvent
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityExplodeEvent
import java.util.logging.Logger

/**
 * Cleans up shop records when explosions destroy linked containers (REQ-016).
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
class ExplodeCleanupListener(
    private val shopRepository: ShopRepository
) : Listener {

    private val logger = Logger.getLogger(ExplodeCleanupListener::class.java.name)

    @EventHandler(ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) {
        for (block in event.blockList()) {
            if (block.state is Container) {
                val loc = block.location
                val shops = shopRepository.findByContainer(
                    loc.world?.name ?: "world",
                    loc.blockX, loc.blockY, loc.blockZ
                )
                for (shop in shops) {
                    shopRepository.delete(shop.id)
                    Bukkit.getPluginManager().callEvent(ShopDeletedEvent(shop.owner))
                    logger.info("Shop ${shop.id} deleted due to explosion at ${loc.world?.name}:${loc.blockX},${loc.blockY},${loc.blockZ}")
                }
            }
        }
    }
}
