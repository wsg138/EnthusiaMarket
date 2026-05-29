package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.sign.PurchaseSignRepository
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.events.PurchaseSignBrokenEvent
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent

/**
 * Tracks destruction of registered purchase signs (REQ-253):
 * deletes the binding and fires [PurchaseSignBrokenEvent] so other
 * plugins can react.
 *
 * **Auth:** the break is only honoured when the breaker can manage
 * the bound stall — SOLO owner, GUILD member with MANAGE_SHOPS, or
 * `enthusiamarket.admin`. Anyone else's break is cancelled outright
 * so random griefers can't unbind every stall sign in the market.
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class PurchaseSignBreakListener(
    private val signs: PurchaseSignRepository,
    private val stalls: StallRepository,
    private val guildProvider: GuildProvider,
    private val lang: LangService,
) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        if (!isSignMaterial(block.type)) return
        val sign = signs.findAt(block.world.name, block.x, block.y, block.z) ?: return

        val player = event.player
        if (!player.hasPermission("enthusiamarket.admin")) {
            val stall = stalls.findById(sign.stallId)
            if (stall == null || !stall.canManage(player.uniqueId, guildProvider)) {
                event.isCancelled = true
                player.sendMessage(
                    lang.msg("purchase_sign.msg.not_owner", "stall" to sign.stallId.value)
                )
                return
            }
        }

        signs.deleteAt(sign.world, sign.x, sign.y, sign.z)
        Bukkit.getPluginManager().callEvent(
            PurchaseSignBrokenEvent(
                stallId = sign.stallId.value,
                world = sign.world,
                x = sign.x, y = sign.y, z = sign.z,
                breakerUuid = player.uniqueId,
            )
        )
        player.sendMessage(lang.msg("purchase_sign.msg.broken", "stall" to sign.stallId.value))
    }

    private fun isSignMaterial(m: Material): Boolean =
        Tag.SIGNS.isTagged(m) || Tag.WALL_SIGNS.isTagged(m)
}
