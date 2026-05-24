package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.ShopTradeService
import net.badgersmc.em.domain.shop.SignRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin

/**
 * Listens for player interact events on registered shop signs
 * and delegates to ShopTradeService (REQ-006 entry point).
 */
@Component
open class SignInteractListener(
    private val signRepository: SignRepository,
    private val shopTradeService: ShopTradeService
) : Listener {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin
            ?: return
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onSignInteract(event: PlayerInteractEvent) {
        // 0. Filter to main hand only to avoid duplicate processing from off-hand trigger
        if (event.hand != EquipmentSlot.HAND) return

        // 1. Verify player interaction is a right-click
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val block = event.clickedBlock ?: return
        if (!isSign(block)) return

        // 2. Look up sign by location
        val loc = block.location
        val locationStr = serializeLocation(loc)
        val sign = signRepository.bySignLocation(locationStr) ?: return

        // 3. Call ShopTradeService
        val playerUuid = event.player.uniqueId
        val result = shopTradeService.execute(sign.id, playerUuid)

        // 4. Send result message to player
        val message = when (result) {
            is ShopTradeService.TradeResult.Success -> result.description
            is ShopTradeService.TradeResult.Failure -> "\u00A7c${result.reason}"
            is ShopTradeService.TradeResult.RolledBack -> "\u00A7e${result.originalError}"
            is ShopTradeService.TradeResult.CompensationFailed -> "\u00A74[CRITICAL] ${result.originalError} (compensation: ${result.compensationError})"
        }
        event.player.sendMessage(message)
    }

    private fun isSign(block: Block): Boolean {
        return block.state is Sign
    }

    private fun serializeLocation(loc: org.bukkit.Location): String =
        "${loc.world?.name ?: "world"},${loc.blockX},${loc.blockY},${loc.blockZ}"
}