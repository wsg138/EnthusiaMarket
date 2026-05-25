package net.badgersmc.em.infrastructure.listeners

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import net.badgersmc.em.domain.shop.ShopSign
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.shop.SignRepository
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.Component as AdventureComponent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Listens for sign placement events and registers shop signs
 * when a player places a sign with [BUY] or [SELL] text inside a stall region.
 */
@Component
open class SignPlaceListener(
    private val stallRepository: StallRepository,
    private val signRepository: SignRepository
) : Listener {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin
            ?: return
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onSignPlace(event: SignChangeEvent) {
        val block = event.block
        val loc = block.location

        // 1. Check if sign is inside a stall region
        val stall = findStallAt(loc) ?: return

        // 2. Parse the first line of sign text for [BUY] or [SELL]
        val lines = event.lines()
        val firstLine = PlainTextComponentSerializer.plainText().serialize(lines[0]).trim()
        val direction = parseDirection(firstLine) ?: return

        // 3. Parse the other sign lines for item, price, amount
        val itemKey = PlainTextComponentSerializer.plainText().serialize(lines.getOrElse(1) { AdventureComponent.empty() }).trim()
        if (itemKey.isBlank()) return

        val priceText = PlainTextComponentSerializer.plainText().serialize(lines.getOrElse(2) { AdventureComponent.empty() }).trim()
        val price = priceText.toLongOrNull() ?: return
        if (price <= 0) return

        // 4. Register as ShopSign via SignRepository
        val sign = ShopSign(
            id = 0L, // will be assigned by the repository
            stallId = stall.id,
            direction = direction,
            itemKey = itemKey,
            price = price,
            signLocation = serializeLocation(loc),
            containerLocation = "" // container not yet associated
        )
        signRepository.create(sign)

        // 5. Apply sign text formatting
        val headerColor = if (direction == SignDirection.BUY) NamedTextColor.GOLD else NamedTextColor.AQUA
        event.line(0, AdventureComponent.text("[${direction.name}]", headerColor))
        event.line(3, AdventureComponent.text("[Shop]", NamedTextColor.GOLD))
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
            val regionId = region.id
            val stall = stallRepository.findByRegion(world.name, regionId)
            if (stall != null) return stall
        }
        return null
    }

    private fun parseDirection(text: String): SignDirection? {
        val upper = text.uppercase().trim()
        return when {
            upper == "[BUY]" || upper == "BUY" -> SignDirection.BUY
            upper == "[SELL]" || upper == "SELL" -> SignDirection.SELL
            else -> null
        }
    }

    private fun serializeLocation(loc: Location): String =
        "${loc.world?.name ?: "world"},${loc.blockX},${loc.blockY},${loc.blockZ}"
}