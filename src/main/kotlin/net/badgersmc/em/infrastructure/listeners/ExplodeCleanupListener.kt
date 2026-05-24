package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.events.ShopDeletedEvent
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger

/**
 * Cleans up shop records when explosions destroy linked containers (REQ-016).
 */
@Component
class ExplodeCleanupListener(
    private val shopRepository: ShopRepository,
    private val logger: Logger
) : Listener {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin ?: return
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

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
