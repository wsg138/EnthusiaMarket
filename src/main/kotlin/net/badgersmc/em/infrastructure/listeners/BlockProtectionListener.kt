package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.events.ShopDeletedEvent
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

        if (block.state is Sign) {
            val shop = findShopBySign(block)
            if (shop != null) {
                cancelSignBreak(event, shop, event.player)
            }
            return
        }

        if (block.state is Container) {
            val shops = findShopsByContainer(block)
            if (shops.isNotEmpty()) {
                handleContainerBreak(event, block, shops)
            }
        }
    }

    private fun findShopBySign(block: org.bukkit.block.Block): Shop? {
        val loc = block.location
        return shopRepository.findBySign(
            loc.world?.name ?: "world", loc.blockX, loc.blockY, loc.blockZ
        )
    }

    private fun findShopsByContainer(block: org.bukkit.block.Block): List<Shop> {
        val loc = block.location
        return shopRepository.findByContainer(
            loc.world?.name ?: "world", loc.blockX, loc.blockY, loc.blockZ
        )
    }

    private fun cancelSignBreak(event: BlockBreakEvent, shop: Shop, player: org.bukkit.entity.Player) {
        event.isCancelled = true
        val isOwner = player.uniqueId == shop.owner || player.hasPermission("enthusiamarket.admin")
        val isTrusted = shop.trusted.contains(player.uniqueId)
        if (isOwner || isTrusted) {
            player.sendMessage("§e[Shop] Use the edit menu to delete this shop (coming in TDD-57)")
        } else {
            player.sendMessage("§cYou cannot break this shop sign")
        }
    }

    private fun handleContainerBreak(event: BlockBreakEvent, block: org.bukkit.block.Block, shops: List<Shop>) {
        val player = event.player
        val isOwner = shops.all { it.owner == player.uniqueId } || player.hasPermission("enthusiamarket.admin")
        if (!isOwner) {
            event.isCancelled = true
            player.sendMessage("§cThis container has active shops. Only the owner can break it.")
            return
        }

        val loc = block.location
        try {
            shopRepository.deleteByContainer(loc.world?.name ?: "world", loc.blockX, loc.blockY, loc.blockZ)
            for (shop in shops) {
                Bukkit.getPluginManager().callEvent(ShopDeletedEvent(shop.owner))
            }
            logger.info("Deleted ${shops.size} shop(s) at ${loc.world?.name}:${loc.blockX},${loc.blockY},${loc.blockZ}")
            player.sendMessage("§aDeleted ${shops.size} shop(s) linked to this container")
        } catch (e: Exception) {
            logger.severe("Failed to delete shops at container break: ${e.message}")
            player.sendMessage("§cAn error occurred while deleting shops. Please contact an admin.")
        }
    }
}
