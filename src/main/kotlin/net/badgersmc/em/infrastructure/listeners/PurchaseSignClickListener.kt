package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.StallBuyoutService
import net.badgersmc.em.application.StallRentExtensionService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.sign.PurchaseSignRepository
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Right-click on a registered purchase sign:
 *
 * - **UNOWNED** stall → [StallBuyoutService.buy] at the sign's price.
 * - **OWNED / GRACE** stall → two-step extension flow. First click
 *   stages a pending confirm; second click within `confirmWindowSec`
 *   calls [StallRentExtensionService.extend]. Pending confirms are
 *   keyed on (player, sign-location) so two players can confirm
 *   different signs independently.
 * - **AUCTIONING** stall → informational message; auction flow uses
 *   `/em bid`.
 *
 * Event is cancelled in every routed branch so the click never falls
 * through to vanilla sign edit behaviour.
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class PurchaseSignClickListener(
    private val signs: PurchaseSignRepository,
    private val stalls: StallRepository,
    private val buyout: StallBuyoutService,
    private val rentExtension: StallRentExtensionService,
    private val config: EnthusiaMarketConfig,
    private val lang: LangService,
) : Listener {

    private data class ConfirmKey(val player: UUID, val locationKey: String)

    /**
     * Tracks pending extension confirmations. Cleared on expiry or on
     * the confirming second click. ConcurrentHashMap so click events
     * (main-thread today but could be plugin-listener-rescheduled
     * later) stay safe.
     */
    private val pendingConfirms = ConcurrentHashMap<ConfirmKey, Instant>()

    @EventHandler(ignoreCancelled = true)
    fun onClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (!isSignMaterial(block.type)) return

        val sign = signs.findAt(block.world.name, block.x, block.y, block.z) ?: return
        val player = event.player
        event.isCancelled = true

        val stall = stalls.findById(sign.stallId)
        if (stall == null) {
            player.sendMessage(lang.msg("purchase_sign.msg.stall_missing", "stall" to sign.stallId.value))
            return
        }

        when (stall.state) {
            StallState.UNOWNED -> {
                if (!player.hasPermission("enthusiamarket.stall.buyout")) {
                    player.sendMessage(lang.msg("purchase_sign.msg.buy_no_permission"))
                    return
                }
                handleBuy(player.uniqueId, sign.stallId, sign.price, player)
            }
            StallState.AUCTIONING, StallState.RE_AUCTIONING, StallState.EMERGENCY_AUCTIONING ->
                player.sendMessage(
                    lang.msg("purchase_sign.msg.auction_live", "stall" to sign.stallId.value)
                )
            StallState.OWNED, StallState.GRACE ->
                handleExtension(player.uniqueId, sign.stallId, sign.locationKey, player)
        }
    }

    private fun handleBuy(actor: UUID, stallId: net.badgersmc.em.domain.stall.StallId, price: Long, player: org.bukkit.entity.Player) {
        // Sneak + right-click = "buy for my guild". Plain right-click =
        // personal buyout. Guild path charges the actor personally and
        // awards to OwnerRef.guild — the LumaGuilds bank isn't a UUID-
        // addressable economy account in the current Vault setup, so
        // routing the debit through the actor is the cleanest option.
        val result = if (player.isSneaking) {
            buyout.buyForGuild(stallId, actor, price)
        } else {
            buyout.buy(stallId, actor, price)
        }
        val msg = when (result) {
            is StallBuyoutService.Result.Purchased -> {
                val key = if (result.owner.type == net.badgersmc.em.domain.stall.OwnerType.GUILD) {
                    "purchase_sign.msg.purchased_guild"
                } else {
                    "purchase_sign.msg.purchased"
                }
                lang.msg(
                    key,
                    "stall" to stallId.value,
                    "price" to result.price,
                )
            }
            is StallBuyoutService.Result.NotFound ->
                lang.msg("purchase_sign.msg.stall_missing", "stall" to stallId.value)
            is StallBuyoutService.Result.AuctionLive ->
                lang.msg("purchase_sign.msg.auction_live", "stall" to stallId.value)
            is StallBuyoutService.Result.AlreadyOwned ->
                lang.msg("purchase_sign.msg.already_owned", "stall" to stallId.value)
            is StallBuyoutService.Result.NotInGuild ->
                lang.msg("purchase_sign.msg.not_in_guild")
            is StallBuyoutService.Result.NoGuildPermission ->
                lang.msg("purchase_sign.msg.no_guild_permission")
            is StallBuyoutService.Result.Rejected ->
                lang.msg("purchase_sign.msg.rejected", "reason" to result.reason)
        }
        player.sendMessage(msg)
    }

    private fun handleExtension(
        actor: UUID,
        stallId: net.badgersmc.em.domain.stall.StallId,
        locationKey: String,
        player: org.bukkit.entity.Player,
    ) {
        val key = ConfirmKey(actor, locationKey)
        val now = Instant.now()
        val windowSec = config.signs.confirmWindowSec.coerceAtLeast(1)
        val window = Duration.ofSeconds(windowSec.toLong())
        pendingConfirms.entries.removeIf { Duration.between(it.value, now) > window }

        val pending = pendingConfirms[key]
        val isFreshConfirm = pending != null && Duration.between(pending, now) <= window

        if (!isFreshConfirm) {
            pendingConfirms[key] = now
            player.sendMessage(lang.msg(
                "purchase_sign.msg.confirm_extend",
                "stall" to stallId.value,
                "seconds" to config.signs.confirmWindowSec,
            ))
            return
        }

        pendingConfirms.remove(key)

        val msg = when (val r = rentExtension.extend(stallId, actor)) {
            is StallRentExtensionService.Result.Extended -> lang.msg(
                "purchase_sign.msg.extended",
                "stall" to stallId.value,
                "amount" to r.amountPaid,
            )
            is StallRentExtensionService.Result.NotAuthorised ->
                lang.msg("purchase_sign.msg.not_owner", "stall" to stallId.value)
            is StallRentExtensionService.Result.NotFound,
            is StallRentExtensionService.Result.NotOwned ->
                lang.msg("purchase_sign.msg.stall_missing", "stall" to stallId.value)
            is StallRentExtensionService.Result.Rejected ->
                lang.msg("purchase_sign.msg.rejected", "reason" to r.reason)
        }
        player.sendMessage(msg)
    }

    private fun isSignMaterial(m: Material): Boolean =
        Tag.SIGNS.isTagged(m) || Tag.WALL_SIGNS.isTagged(m)
}
