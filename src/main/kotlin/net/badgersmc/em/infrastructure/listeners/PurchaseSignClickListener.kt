package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.StallBuyoutService
import net.badgersmc.em.application.StallRentExtensionService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.sign.PurchaseSignRepository
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.interaction.gui.PurchaseMethodMenu
import net.badgersmc.em.interaction.MenuFactory
import net.badgersmc.em.interaction.bedrock.BedrockPurchaseMethodForm
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component as AdventureComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
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
    private val auctions: AuctionRepository,
    private val buyout: StallBuyoutService,
    private val rentExtension: StallRentExtensionService,
    private val config: EnthusiaMarketConfig,
    private val lang: LangService,
    private val guildProvider: GuildProvider? = null,
    private val menuFactory: MenuFactory? = null,
) : Listener {

    private val logger = java.util.logging.Logger.getLogger(PurchaseSignClickListener::class.java.name)

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
                handleBuy(sign.stallId, sign.price, player)
            }
            StallState.AUCTIONING, StallState.RE_AUCTIONING, StallState.EMERGENCY_AUCTIONING -> {
                player.sendMessage(
                    lang.msg("purchase_sign.msg.auction_live", "stall" to sign.stallId.value)
                )
                val auction = auctions.findOpenByStall(sign.stallId)
                if (auction != null) {
                    val cmd = "/em bid ${auction.id} "
                    player.sendMessage(
                        AdventureComponent.text("  /em bid ", NamedTextColor.GRAY)
                            .append(AdventureComponent.text(auction.id.value, NamedTextColor.YELLOW)
                                .clickEvent(ClickEvent.suggestCommand(cmd)))
                            .append(AdventureComponent.text(" <amount>", NamedTextColor.GRAY))
                    )
                }
            }
            StallState.OWNED, StallState.GRACE ->
                handleExtension(player.uniqueId, sign.stallId, sign.locationKey, player)
        }
    }

    private fun handleBuy(stallId: net.badgersmc.em.domain.stall.StallId, price: Long, player: org.bukkit.entity.Player) {
        // Open the purchase method menu — player chooses personal or guild buyout.
        // Guild option only appears if the player is in a guild with MANAGE_SHOPS.
        openPurchaseMethodMenu(stallId, price, player)
    }

    /** Overridable for testability — opens the PurchaseMethodMenu. */
    open fun openPurchaseMethodMenu(stallId: net.badgersmc.em.domain.stall.StallId, price: Long, player: org.bukkit.entity.Player) {
        if (menuFactory?.shouldUseBedrockMenus(player) == true) {
            BedrockPurchaseMethodForm(player, stallId, price, buyout, guildProvider, logger, lang).open(player)
        } else {
            PurchaseMethodMenu(stallId, price, buyout, guildProvider, lang).open(player)
        }
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
