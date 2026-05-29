package net.badgersmc.em.infrastructure.listeners

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.events.ShopCreatedEvent
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.Component as AdventureComponent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.block.data.type.WallSign
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

/**
 * Container-linked sign shop creation in the ItemShops / ChestShop /
 * Essentials style: hold the item, place a wall sign on a chest
 * inside a stall you own, fill in the lines.
 *
 * ```
 * Line 1: [BUY]      ← or [SELL]
 * Line 2: 64         ← amount per trade
 * Line 3: 1000       ← price per trade (Vault currency)
 * Line 4: (blank)    ← plugin overwrites with [Shop]
 * ```
 *
 * The traded item is taken from the player's main hand at placement
 * time. Auto-formats every line on success. Right-click on the
 * finished sign opens the existing purchase/sell flow via
 * [ShopInteractListener] + ContainerTradeService.
 */
@Component
open class SignPlaceListener(
    private val stallRepository: StallRepository,
    private val shopRepository: ShopRepository,
    private val guildProvider: GuildProvider,
    private val lang: LangService,
) : Listener {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin
            ?: return
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onSignPlace(event: SignChangeEvent) {
        val player = event.player
        val plain = PlainTextComponentSerializer.plainText()
        val lines = event.lines()
        val firstLine = plain.serialize(lines[0]).trim().uppercase()

        val direction = when (firstLine) {
            "[BUY]", "BUY" -> SignDirection.BUY
            "[SELL]", "SELL" -> SignDirection.SELL
            else -> return
        }

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
            return
        }
        val attached = block.getRelative(data.facing.oppositeFace)
        if (attached.state !is Container) {
            player.sendMessage(lang.msg("shop.create.needs_container"))
            return
        }

        // Sign must be inside a stall the player can manage.
        val stall = findStallAt(block.location) ?: run {
            player.sendMessage(lang.msg("shop.create.not_in_stall"))
            return
        }
        if (!canManageStall(stall, player)) {
            player.sendMessage(lang.msg("shop.create.no_authority"))
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
            return
        }

        // Parse amount + price.
        val amount = plain.serialize(lines.getOrElse(1) { AdventureComponent.empty() })
            .trim().toIntOrNull()
        val price = plain.serialize(lines.getOrElse(2) { AdventureComponent.empty() })
            .trim().toLongOrNull()
        if (amount == null || amount <= 0 || price == null || price <= 0) {
            player.sendMessage(lang.msg("shop.create.invalid_input"))
            return
        }

        // Held item = the traded item. Player needs to be holding it.
        val held = player.inventory.itemInMainHand
        if (held.type == Material.AIR || held.amount <= 0) {
            player.sendMessage(lang.msg("shop.create.no_held_item"))
            return
        }
        val sellStack = held.clone().apply { this.amount = 1 }

        // Persist the Shop bound to (sign, container).
        val shop = Shop(
            stallId = stall.id.value,
            owner = player.uniqueId,
            signWorld = signLoc.world?.name ?: "world",
            signX = signLoc.blockX,
            signY = signLoc.blockY,
            signZ = signLoc.blockZ,
            containerWorld = attached.world.name,
            containerX = attached.x,
            containerY = attached.y,
            containerZ = attached.z,
            sellItem = ItemStackSerializer.serialize(sellStack),
            sellAmount = amount,
            // costItem is a UI hint for the GUI's "you pay" slot. Real
            // money flow runs through EconomyProvider; park an emerald
            // icon here for display purposes.
            costItem = ItemStackSerializer.serialize(ItemStack(Material.EMERALD, 1)),
            costAmount = price.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            creatorId = player.uniqueId,
            direction = direction,
        )
        shopRepository.upsert(shop)

        // Auto-format the four lines.
        val headerColor = if (direction == SignDirection.BUY) NamedTextColor.GOLD else NamedTextColor.AQUA
        event.line(0, AdventureComponent.text("[${direction.name}]", headerColor))
        event.line(1, AdventureComponent.text("${amount}x ${held.type.name.lowercase()}", NamedTextColor.WHITE))
        event.line(2, AdventureComponent.text("$price", NamedTextColor.GOLD))
        event.line(3, AdventureComponent.text("[Shop]", NamedTextColor.GOLD))

        player.sendMessage(lang.msg("shop.create.success"))
        try {
            Bukkit.getPluginManager().callEvent(ShopCreatedEvent(player.uniqueId))
        } catch (_: Throwable) {
            // External listener failure must not roll back the create.
        }
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
