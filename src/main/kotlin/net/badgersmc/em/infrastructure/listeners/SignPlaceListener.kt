package net.badgersmc.em.infrastructure.listeners

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopSignRenderer
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.events.ShopCreatedEvent
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
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
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class SignPlaceListener(
    private val stallRepository: StallRepository,
    private val shopRepository: ShopRepository,
    private val guildProvider: GuildProvider,
    private val lang: LangService,
    private val signRenderer: ShopSignRenderer,
) : Listener {
    private val plain = PlainTextComponentSerializer.plainText()

    @EventHandler
    fun onSignPlace(event: SignChangeEvent) {
        val player = event.player
        val lines = event.lines()
        val direction = direction(lines) ?: return

        val location = creationLocation(event) ?: return

        val trade = trade(event, direction, location.stall) ?: return
        persistAndRender(event, direction, location, trade)
        player.sendMessage(lang.msg("shop.create.success"))
        notifyCreated(player)
    }

    private fun persistAndRender(
        event: SignChangeEvent,
        direction: SignDirection,
        location: CreationLocation,
        trade: TradeDefinition,
    ) {
        val sign = event.block.location
        val container = location.container
        shopRepository.upsert(Shop(
            stallId = location.stall.id.value,
            owner = event.player.uniqueId,
            signWorld = sign.world?.name ?: "world",
            signX = sign.blockX,
            signY = sign.blockY,
            signZ = sign.blockZ,
            containerWorld = container.world.name,
            containerX = container.x,
            containerY = container.y,
            containerZ = container.z,
            sellItem = trade.sellItem,
            sellAmount = trade.amount,
            costItem = trade.cost.item,
            costAmount = trade.cost.amount,
            direction = direction,
        ))
        val lines = signRenderer.lines(
            direction,
            trade.held.type.name.lowercase(),
            trade.amount,
            trade.cost.display,
            trade.held.itemMeta?.displayName(),
        )
        lines.forEachIndexed(event::line)
    }

    private fun notifyCreated(player: Player) {
        try {
            Bukkit.getPluginManager().callEvent(ShopCreatedEvent(player.uniqueId))
        } catch (_: Throwable) {
            // External listener failure must not roll back the create.
        }
    }

    private fun direction(lines: List<AdventureComponent>): SignDirection? =
        when (plain.serialize(lines[0]).trim().uppercase()) {
            "[BUY]", "BUY" -> SignDirection.BUY
            "[SELL]", "SELL" -> SignDirection.SELL
            "[TRADE]", "TRADE" -> SignDirection.TRADE
            else -> null
        }

    private fun creationLocation(event: SignChangeEvent): CreationLocation? {
        val player = event.player
        if (!player.hasPermission("enthusiamarket.shop.create")) {
            return reject(event, "shop.create.no_permission")
        }
        val container = attachedContainer(event) ?: return null
        val stall = findStallAt(event.block.location)
            ?: return reject(event, "shop.create.not_in_stall")
        if (!canManageStall(stall, player)) return reject(event, "shop.create.no_authority")
        val sign = event.block.location
        val existing = shopRepository.findBySign(
            sign.world?.name ?: "world",
            sign.blockX,
            sign.blockY,
            sign.blockZ,
        )
        if (existing != null) return reject(event, "shop.create.already_shop")
        return CreationLocation(stall, container)
    }

    private fun attachedContainer(event: SignChangeEvent): org.bukkit.block.Block? {
        val data = event.block.blockData
        if (data !is WallSign) return reject(event, "shop.create.needs_wallsign")
        val container = event.block.getRelative(data.facing.oppositeFace)
        if (container.state !is Container) return reject(event, "shop.create.needs_container")
        return container
    }

    private fun reject(event: SignChangeEvent, message: String): Nothing? {
        event.player.sendMessage(lang.msg(message))
        event.isCancelled = true
        return null
    }

    private data class CreationLocation(
        val stall: Stall,
        val container: org.bukkit.block.Block,
    )

    private fun trade(
        event: SignChangeEvent,
        direction: SignDirection,
        stall: Stall,
    ): TradeDefinition? {
        val lines = event.lines()
        val amount = plain.serialize(lines.getOrElse(1) { AdventureComponent.empty() })
            .trim()
            .toIntOrNull()
        if (amount == null || amount <= 0) return reject(event, "shop.create.invalid_input")
        val held = event.player.inventory.itemInMainHand
        if (held.type == Material.AIR || held.amount <= 0) {
            return reject(event, "shop.create.no_held_item")
        }
        val cost = cost(event, direction, stall) ?: return null
        val sellStack = held.clone().apply { this.amount = 1 }
        return TradeDefinition(
            held = held,
            sellItem = ItemStackSerializer.serialize(sellStack),
            amount = amount,
            cost = cost,
        )
    }

    private fun cost(
        event: SignChangeEvent,
        direction: SignDirection,
        stall: Stall,
    ): CostDefinition? = when (direction) {
        SignDirection.BUY, SignDirection.SELL -> currencyCost(event)
        SignDirection.TRADE -> barterCost(event, stall)
    }

    private fun currencyCost(event: SignChangeEvent): CostDefinition? {
        val price = plain.serialize(event.lines().getOrElse(2) { AdventureComponent.empty() })
            .trim()
            .toLongOrNull()
        if (price == null || price <= 0 || price > Int.MAX_VALUE.toLong()) {
            return reject(event, "shop.create.invalid_input")
        }
        return CostDefinition(
            ItemStackSerializer.serialize(ItemStack(Material.RAW_GOLD, 1)),
            price.toInt(),
            "$price",
        )
    }

    private fun barterCost(event: SignChangeEvent, stall: Stall): CostDefinition? {
        if (stall.owner.type == OwnerType.GUILD) {
            return reject(event, "shop.create.no_guild_trade")
        }
        val text = plain.serialize(event.lines().getOrElse(2) { AdventureComponent.empty() }).trim()
        val parts = text.split("\\s+".toRegex(), limit = 2)
        val amount = parts.getOrNull(0)?.toIntOrNull()
        val material = runCatching { Material.valueOf(parts.getOrNull(1)?.uppercase().orEmpty()) }.getOrNull()
        if (!validBarter(amount, material)) {
            return reject(event, "shop.create.invalid_trade_cost")
        }
        requireNotNull(amount)
        requireNotNull(material)
        return CostDefinition(
            ItemStackSerializer.serialize(ItemStack(material, 1)),
            amount,
            "$amount ${material.name.lowercase()}",
        )
    }

    private fun validBarter(amount: Int?, material: Material?): Boolean {
        if (amount == null || amount <= 0) return false
        return material?.isItem == true
    }

    private data class TradeDefinition(
        val held: ItemStack,
        val sellItem: String,
        val amount: Int,
        val cost: CostDefinition,
    )

    private data class CostDefinition(
        val item: String,
        val amount: Int,
        val display: String,
    )

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
