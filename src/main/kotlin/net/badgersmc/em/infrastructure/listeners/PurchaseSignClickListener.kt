package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.SellOfferService
import net.badgersmc.em.domain.sign.PurchaseSignKind
import net.badgersmc.em.domain.sign.PurchaseSignRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Routes right-clicks on registered purchase signs (REQ-250):
 *
 * - [PurchaseSignKind.BUY]: invokes [SellOfferService.purchase].
 * - Other kinds: surface a "not yet implemented" lang message —
 *   the bindings exist so signs can be pre-placed, but the action
 *   wiring lands in TDD-2xx follow-ups.
 */
@Component
open class PurchaseSignClickListener(
    private val signs: PurchaseSignRepository,
    private val sellOffers: SellOfferService,
    private val lang: LangService,
) : Listener {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin
            ?: return
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (!isSignMaterial(block.type)) return

        val sign = signs.findAt(block.world.name, block.x, block.y, block.z) ?: return
        val player = event.player

        when (sign.kind) {
            PurchaseSignKind.BUY -> {
                val result = sellOffers.purchase(sign.stallId, player.uniqueId)
                val msg = when (result) {
                    is SellOfferService.Result.Purchased -> lang.msg(
                        "offer.purchased",
                        "stall" to sign.stallId.value,
                        "price" to result.offer.price,
                        "tax" to result.tax,
                        "total" to (result.offer.price + result.tax),
                    )
                    is SellOfferService.Result.NotFound ->
                        lang.msg("purchase_sign.msg.not_for_sale")
                    is SellOfferService.Result.Rejected ->
                        lang.msg("offer.rejected", "reason" to result.reason)
                    else -> lang.msg("offer.rejected", "reason" to result.toString())
                }
                player.sendMessage(msg)
                event.isCancelled = true
            }
            PurchaseSignKind.RENT, PurchaseSignKind.EXTEND -> {
                player.sendMessage(lang.msg("purchase_sign.msg.rent_not_supported"))
                event.isCancelled = true
            }
            PurchaseSignKind.INFO -> {
                player.sendMessage(lang.msg("purchase_sign.msg.info_not_supported"))
                event.isCancelled = true
            }
        }
    }

    private fun isSignMaterial(m: Material): Boolean =
        Tag.SIGNS.isTagged(m) || Tag.WALL_SIGNS.isTagged(m)
}
