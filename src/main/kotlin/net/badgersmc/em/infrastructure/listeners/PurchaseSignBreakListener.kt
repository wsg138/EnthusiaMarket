package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.domain.sign.PurchaseSignRepository
import net.badgersmc.em.events.PurchaseSignBrokenEvent
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Tracks destruction of registered purchase signs (REQ-253):
 * deletes the binding and fires [PurchaseSignBrokenEvent] so other
 * plugins can react.
 */
@Component
open class PurchaseSignBreakListener(
    private val signs: PurchaseSignRepository,
    private val lang: LangService,
) : Listener {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin
            ?: return
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        if (!isSignMaterial(block.type)) return
        val sign = signs.findAt(block.world.name, block.x, block.y, block.z) ?: return

        signs.deleteAt(sign.world, sign.x, sign.y, sign.z)
        Bukkit.getPluginManager().callEvent(
            PurchaseSignBrokenEvent(
                stallId = sign.stallId.value,
                world = sign.world,
                x = sign.x, y = sign.y, z = sign.z,
                breakerUuid = event.player.uniqueId,
            )
        )
        event.player.sendMessage(lang.msg("purchase_sign.msg.broken", "stall" to sign.stallId.value))
    }

    private fun isSignMaterial(m: Material): Boolean =
        Tag.SIGNS.isTagged(m) || Tag.WALL_SIGNS.isTagged(m)
}
