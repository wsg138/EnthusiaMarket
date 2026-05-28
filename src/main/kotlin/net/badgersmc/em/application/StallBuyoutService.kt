package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * Orchestrates outright "click-the-sign-to-buy" stall purchases (REQ-250).
 *
 * Used by [net.badgersmc.em.infrastructure.listeners.PurchaseSignClickListener]
 * to convert a buyer + price + UNOWNED stall into ownership. Designed
 * for the post-initial-auction lifecycle: once the one-shot mass
 * auction has run, every remaining or evicted stall becomes buyable
 * via its purchase sign at the price written on line 3.
 *
 * Compensation order matches `AuctionLifecycleService.settleWithWinner`
 * and `SellOfferService.purchase`: withdraw → persist → fire event.
 * If withdraw fails the buyer is untouched and no state moves.
 */
@Service
class StallBuyoutService(
    private val stalls: StallRepository,
    private val offers: SellOfferRepository,
    private val auctions: net.badgersmc.em.domain.auction.AuctionRepository,
    private val economy: EconomyProvider,
    private val config: EnthusiaMarketConfig,
) {

    private val log = Logger.getLogger(StallBuyoutService::class.java.name)

    sealed interface Result {
        data class Purchased(val stall: Stall, val price: Long) : Result
        data object NotFound : Result
        data object AuctionLive : Result
        data object AlreadyOwned : Result
        data class Rejected(val reason: String) : Result
    }

    fun buy(stallId: StallId, buyer: UUID, price: Long): Result {
        if (price <= 0) return Result.Rejected("Sign price is invalid")

        val stall = stalls.findById(stallId) ?: return Result.NotFound

        // Reject on AUCTIONING — the one-shot initial auction is using the
        // stall right now; click-to-buy is for the post-auction lifecycle.
        if (stall.state == StallState.AUCTIONING ||
            auctions.findOpenByStall(stallId) != null
        ) {
            return Result.AuctionLive
        }

        if (stall.state != StallState.UNOWNED) {
            return Result.AlreadyOwned
        }

        if (!economy.withdraw(buyer, price)) {
            return Result.Rejected("Insufficient funds: $price required")
        }

        val previousState = stall.state
        val updated = try {
            val now = Instant.now()
            val awarded = stall.awardTo(OwnerRef.solo(buyer), price, now)
                .copy(nextRentAt = now.plus(collectionInterval()))
            stalls.save(awarded)
            // Defensive: if a sell offer somehow lingered on an UNOWNED
            // stall, clean it up so a follow-up click doesn't trip the
            // mutex check next door.
            if (offers.findByStall(stallId) != null) {
                offers.delete(stallId)
            }
            awarded
        } catch (e: Exception) {
            log.severe(
                "StallBuyoutService.buy: ownership transfer failed for stall " +
                    "${stallId.value} after charging buyer $buyer price=$price. " +
                    "Manual refund required. cause=${e.message}"
            )
            throw e
        }

        fireStateChanged(stallId.value, previousState, updated.state)
        return Result.Purchased(updated, price)
    }

    private fun collectionInterval(): Duration = try {
        Duration.parse(config.rent.collectionInterval)
    } catch (_: java.time.format.DateTimeParseException) {
        Duration.ofDays(1)
    }

    private fun fireStateChanged(
        stallId: String,
        previous: StallState,
        current: StallState,
    ) {
        if (previous == current) return
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                StallStateChangedEvent(stallId, previous, current)
            )
        } catch (e: Exception) {
            log.warning("Failed to fire StallStateChangedEvent for $stallId: ${e.message}")
        }
    }
}
