package net.badgersmc.em.infrastructure.listeners

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.block.data.type.WallSign
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger

/**
 * Listens for player left-click+sneak on wall signs attached to containers
 * inside owned stalls, opening the CreateShopMenu (REQ-012).
 */
@Component
open class ShopCreateListener(
    private val stallRepository: StallRepository,
    private val shopRepository: ShopRepository
) : Listener {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin
        if (plugin == null) {
            Logger.getLogger(ShopCreateListener::class.java.name)
                .warning("EnthusiaMarket plugin not found — ShopCreateListener will not be registered")
            return
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onSignInteract(event: PlayerInteractEvent) {
        // Must be left-click while sneaking
        if (event.action != Action.LEFT_CLICK_BLOCK) return
        if (!event.player.isSneaking) return

        val block = event.clickedBlock ?: return
        val state = block.state

        // Must be a wall sign
        if (state !is Sign || block.blockData !is WallSign) return

        // Must not already be a registered shop
        val loc = block.location
        if (shopRepository.findBySign(loc.world?.name ?: "world", loc.blockX, loc.blockY, loc.blockZ) != null) {
            event.player.sendMessage("§cThis sign is already a shop")
            return
        }

        // Find attached container via the sign's attached block face
        val wallSignData = block.blockData as WallSign
        val facing = wallSignData.facing
        val attachedBlock = block.getRelative(facing.oppositeFace)

        if (attachedBlock.state !is Container) {
            event.player.sendMessage("§cSign must be attached to a container (chest, barrel, shulker)")
            return
        }

        // Check the sign is inside an owned stall
        val stall = findStallAt(loc) ?: run {
            event.player.sendMessage("§cSign must be inside a registered stall")
            return
        }

        // Check player can manage this stall
        if (!canManageStall(stall, event.player)) {
            event.player.sendMessage("§cYou do not own or rent this stall")
            return
        }

        event.setUseInteractedBlock(Event.Result.DENY)

        // Note: ShopCreatedEvent is fired after successful persistence in the shop creation flow

        // Open CreateShopMenu — for now just a placeholder
        event.player.sendMessage("§e[Shop] Create menu would open here (TDD-52)")
    }

    open fun findStallAt(location: Location): Stall? {
        val world = location.world ?: return null
        val wgWorld = BukkitAdapter.adapt(world)
        val container = WorldGuard.getInstance().platform.regionContainer
        val regionManager = container.get(wgWorld) ?: return null
        val regions = regionManager.getApplicableRegions(BukkitAdapter.asBlockVector(location))
        for (region in regions) {
            val stall = stallRepository.findByRegion(world.name, region.id)
            if (stall != null) return stall
        }
        return null
    }

    open fun canManageStall(stall: Stall, player: Player): Boolean {
        if (player.hasPermission("enthusiamarket.admin")) return true
        return when (stall.owner.type) {
            OwnerType.SOLO -> stall.owner.id == player.uniqueId.toString()
            OwnerType.GUILD -> {
                // TODO: check guild membership/rank via GuildProvider
                player.sendMessage("§cGuild shop management is not yet available")
                false
            }
            OwnerType.NONE -> false
        }
    }
}
