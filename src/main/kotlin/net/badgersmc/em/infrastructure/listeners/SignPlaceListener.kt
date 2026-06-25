package net.badgersmc.em.infrastructure.listeners

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.block.data.type.WallSign
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Simplified shop creation: place a wall sign on a container inside your
 * stall, and a GUI opens to configure the shop. No sign text parsing — the
 * item to sell is auto-detected from the container's contents. Direction,
 * amount, and price are set in the GUI (defaults: SELL, 1, 100).
 *
 * After GUI confirm, the sign text is written automatically by
 * [net.badgersmc.em.interaction.gui.CreateShopMenu].
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class SignPlaceListener(
    private val stallRepository: StallRepository,
    private val shopRepository: ShopRepository,
    private val guildProvider: GuildProvider,
    private val lang: LangService,
    private val config: net.badgersmc.em.config.EnthusiaMarketConfig,
) : Listener {

    @EventHandler
    @Suppress("LongMethod", "ReturnCount")
    fun onSignPlace(event: SignChangeEvent) {
        val player = event.player

        if (!player.hasPermission("enthusiamarket.shop.create")) {
            player.sendMessage(lang.msg("shop.create.no_permission"))
            event.isCancelled = true
            return
        }

        val block = event.block

        // Must be a wall sign attached to a container.
        val data = block.blockData
        if (data !is WallSign) {
            player.sendMessage(lang.msg("shop.create.needs_wallsign"))
            event.isCancelled = true
            return
        }
        val attached = block.getRelative(data.facing.oppositeFace)
        if (attached.state !is Container) {
            player.sendMessage(lang.msg("shop.create.needs_container"))
            event.isCancelled = true
            return
        }

        // Sign must be inside a stall the player can manage.
        val stall = findStallAt(block.location) ?: run {
            player.sendMessage(lang.msg("shop.create.not_in_stall"))
            event.isCancelled = true
            return
        }

        if (!canManageStall(stall, player)) {
            player.sendMessage(lang.msg("shop.create.no_authority"))
            event.isCancelled = true
            return
        }

        // Already a shop? Don't double-register.
        val signLoc = block.location
        if (shopRepository.findBySign(
                signLoc.world?.name ?: "world",
                signLoc.blockX, signLoc.blockY, signLoc.blockZ,
            ) != null
        ) {
            player.sendMessage(lang.msg("shop.create.already_shop"))
            event.isCancelled = true
            return
        }

        // Scan container for the item to sell.
        val container = attached.state as Container
        val sellItem = container.inventory.contents
            .filterNotNull()
            .firstOrNull { it.type != Material.AIR && it.amount > 0 }
        if (sellItem == null) {
            player.sendMessage(lang.msg("shop.create.empty_container"))
            event.isCancelled = true
            return
        }
        val sellClone = sellItem.clone(); sellClone.amount = 1
        val sellItemB64 = ItemStackSerializer.serialize(sellClone)

        // Open GUI — player picks direction, amount, price there.
        event.isCancelled = true
        val guildId = if (stall.owner.type == OwnerType.GUILD) stall.owner.id else null
        openCreateGui(
            ShopCreateParams(
                stallId = stall.id.value,
                owner = player.uniqueId,
                signLoc = signLoc,
                containerLoc = attached.location,
                sellItemB64 = sellItemB64,
                guildId = guildId,
            ),
            player = player,
        )
    }

    /** Open for testability — override in tests to bypass IFramework GUI creation. */
    open fun openCreateGui(params: ShopCreateParams, player: Player) {
        net.badgersmc.em.interaction.gui.CreateShopMenu(
            stallId = params.stallId, stallOwner = params.owner,
            signLoc = params.signLoc, containerLoc = params.containerLoc,
            sellItemBase64 = params.sellItemB64, shopRepository = shopRepository, lang = lang,
            guildId = params.guildId,
        ).open(player)
    }

    /**
     * Find a stall at the given location by checking WorldGuard regions.
     * Open for testability — override or spy in tests.
     */
    open fun findStallAt(location: Location): Stall? {
        val world = location.world ?: return null
        val wgWorld = BukkitAdapter.adapt(world)
        val container = WorldGuard.getInstance().platform.regionContainer
        val regionManager = container.get(wgWorld) ?: return null

        val regions = regionManager.getApplicableRegions(
            BukkitAdapter.asBlockVector(location)
        )

        for (region in regions) {
            val stall = stallRepository.findByRegion(world.name, region.id)
            if (stall != null) return stall
        }
        return null
    }

    /**
     * Same gate the rest of the codebase uses for "can the player
     * act on this stall" — SOLO owner UUID match, or guild member
     * with MANAGE_SHOPS for GUILD-owned stalls. `enthusiamarket.admin`
     * bypasses for ops.
     */
    open fun canManageStall(stall: Stall, player: Player): Boolean {
        if (player.hasPermission("enthusiamarket.admin")) return true
        return stall.canManage(player.uniqueId, guildProvider)
    }
}

/**
 * Bundled parameters for shop creation via GUI.
 */
data class ShopCreateParams(
    val stallId: String,
    val owner: UUID,
    val signLoc: Location,
    val containerLoc: Location,
    val sellItemB64: String,
    val guildId: String? = null,
)
