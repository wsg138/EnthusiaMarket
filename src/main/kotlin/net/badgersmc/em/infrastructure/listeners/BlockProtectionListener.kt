package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger

/**
 * Protects shop signs and linked containers from destruction (REQ-015).
 *
 * - Breaking a shop sign -> cancelled, owner gets edit menu notification
 * - Breaking a container with linked shops -> cascading delete for owners,
 *   cancellation for non-owners
 */
@Component
class BlockProtectionListener(
    private val shopRepository: ShopRepository,
    private val logger: Logger
) : Listener {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin ?: return
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        val player = event.player

        // Handle sign break
        if (block.state is Sign) {
            val loc = block.location
            val shop = shopRepository.findBySign(
                loc.world?.name ?: "world",
                loc.blockX, loc.blockY, loc.blockZ
            )
            if (shop != null) {
                event.isCancelled = true
                val isOwner = player.uniqueId == shop.owner || player.hasPermission("enthusiamarket.admin")
                val isTrusted = shop.trusted.contains(player.uniqueId)
                if (isOwner || isTrusted) {
                    player.sendMessage("§e[Shop] Use the edit menu to delete this shop (coming in TDD-57)")
                } else {
                    player.sendMessage("§cYou cannot break this shop sign")
                }
                return
            }
        }

        // Handle container break
        if (block.state is Container) {
            val loc = block.location
            val shops = shopRepository.findByContainer(
                loc.world?.name ?: "world",
                loc.blockX, loc.blockY, loc.blockZ
            )
            if (shops.isNotEmpty()) {
                val isOwner = shops.all { it.owner == player.uniqueId } || player.hasPermission("enthusiamarket.admin")
                if (isOwner) {
                    // Delete all linked shops
                    for (shop in shops) {
                        shopRepository.delete(shop.id)
                        logger.info("Shop ${shop.id} deleted due to container break by ${player.name}")
                    }
                    player.sendMessage("§aDeleted ${shops.size} shop(s) linked to this container")
                } else {
                    event.isCancelled = true
                    player.sendMessage("§cThis container has active shops. Only the owner can break it.")
                }
            }
        }
    }
}