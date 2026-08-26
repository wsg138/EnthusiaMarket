package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.offer.SellOffer
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.MarketAcquisitionBlockedException
import net.badgersmc.em.domain.ports.MarketModerationPolicy
import net.badgersmc.em.domain.ports.MarketMutationGate
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.events.SellOfferCompletedEvent
import net.badgersmc.em.events.SellOfferCreatedEvent
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * Orchestrates the ARM-style sell-offer flow (REQ-260..264).
 *
 * - [create]: owner-only; rejects when an auction is already open on
 *   the stall (REQ-263).
 * - [purchase]: withdraws `price * (1 + taxPct)` from the buyer,
 *   deposits `price` to the seller, deposits `price * taxPct` to the
 *   configured tax destination (REQ-264). Compensating actions on
 *   downstream failures match the auction settlement pattern: charge
 *   first, then persist, then pay; if a later step fails the earlier
 *   ones don't double-execute on retry.
 * - [cancel]: owner-only; deletes the offer without transferring.
 */
@Service
class SellOfferService(
    private val offers: SellOfferRepository,
    private val stalls: StallRepository,
    private val auctions: AuctionRepository,
    private val economy: EconomyProvider,
    private val config: EnthusiaMarketConfig,
    private val guildProvider: GuildProvider,
    private val limits: LimitResolutionService,
    private val ownership: StallOwnershipCounter,
    private val alerter: CompensationAlertService,
    private val moderationPolicy: MarketModerationPolicy = MarketModerationPolicy.AllowAll,
    private val mutationGate: MarketMutationGate = MarketMutationGate.Open,
) {

    private val log = Logger.getLogger(SellOfferService::class.java.name)

    sealed interface Result {
        data class Created(val offer: SellOffer) : Result
        data class Purchased(val offer: SellOffer, val tax: Long) : Result
        data class Cancelled(val offer: SellOffer) : Result
        data object NotFound : Result
        data object NotAuthorised : Result
        data object AuctionOpen : Result
        data object OfferOpen : Result
        data class Rejected(val reason: String) : Result
    }

    fun create(stallId: StallId, seller: UUID, price: Long): Result {
        if (price <= 0) return Result.Rejected("Price must be positive")

        val stall = stalls.findById(stallId) ?: return Result.NotFound
        if (!stall.canManage(seller, guildProvider)) return Result.NotAuthorised
        if (offers.findByStall(stallId) != null) return Result.OfferOpen
        if (auctions.findOpenByStall(stallId) != null) return Result.AuctionOpen

        val offer = SellOffer(stallId, seller, price, Instant.now())
        offers.save(offer)
        Bukkit.getServer()?.pluginManager?.callEvent(
            SellOfferCreatedEvent(stallId.value, seller, price)
        )
        return Result.Created(offer)
    }

    fun cancel(stallId: StallId, actor: UUID): Result {
        val offer = offers.findByStall(stallId) ?: return Result.NotFound
        if (offer.sellerUuid != actor) {
            val stall = stalls.findById(stallId)
            // Guild owners with MANAGE_SHOPS can also cancel; falls back
            // to the same canManage rule the rest of the codebase uses.
            if (stall == null || !stall.canManage(actor, guildProvider)) {
                return Result.NotAuthorised
            }
        }
        offers.delete(stallId)
        return Result.Cancelled(offer)
    }

    fun purchase(stallId: StallId, buyer: UUID): Result {
        return try {
            moderationPolicy.withAcquisitionPermit(buyer) {
                purchaseWithPermit(stallId, buyer)
            }
        } catch (blocked: MarketAcquisitionBlockedException) {
            Result.Rejected(blocked.message ?: "Market acquisitions are restricted")
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun purchaseWithPermit(stallId: StallId, buyer: UUID): Result {
        if (mutationGate.isStallLocked(stallId.value)) {
            return Result.Rejected("This stall is temporarily unavailable")
        }
        val offer = offers.findByStall(stallId) ?: return Result.NotFound
        val stall = stalls.findById(stallId) ?: return Result.NotFound

        if (buyer == offer.sellerUuid) {
            return Result.Rejected("You cannot buy your own stall")
        }
        if (!stall.canManage(offer.sellerUuid, guildProvider)) {
            return Result.Rejected("This sell offer is no longer valid")
        }

        val taxPct = config.shop.taxPct
        if (taxPct < 0.0 || taxPct > 1.0) {
            return Result.Rejected("Invalid tax percentage: $taxPct")
        }
        val tax = (offer.price * taxPct).toLong()
        val total = offer.price + tax

        // M1: ownership-cap gate. StallBuyoutService enforces the same
        // limit on a fresh click-to-buy; sell-offer purchase also flips
        // the stall's owner to the buyer, so the cap must apply here too.
        // Run BEFORE the economy withdraw — refusing after charging would
        // force a refund path. LimitResolutionService.canClaim returns
        // Allowed for unlimited groups, so the existing tests (no limits
        // configured) still pass.
        val counts = ownership.counts(buyer)
        when (val decision = limits.canClaim(buyer, stall.kind, counts.total, counts.byKind[stall.kind] ?: 0)) {
            is LimitResolutionService.ClaimDecision.Rejected.TotalCapReached ->
                return Result.Rejected("Stall limit reached (${decision.cap})")
            is LimitResolutionService.ClaimDecision.Rejected.KindCapReached ->
                return Result.Rejected("Limit reached for ${decision.kind} stalls (${decision.cap})")
            LimitResolutionService.ClaimDecision.Allowed -> { /* proceed */ }
        }

        // 0. Withdraw total from buyer. If this fails the buyer wasn't
        // charged — bail without touching ownership or the seller.
        if (!economy.withdraw(buyer, total)) {
            return Result.Rejected("Insufficient funds: $total required")
        }

        // 1. Persist ownership, then close the offer. A repository fence can
        // still win after the fast moderation check, so a failed stall save
        // refunds the buyer before returning.
        val previousState = stall.state
        // M-18: capture the pre-transfer owner so step 2 can route
        // proceeds correctly (guild bank vs seller wallet). `stall`
        // is the un-transferred load; `awardTo` returns a copy and
        // never mutates it. Buyer always becomes the personal owner
        // below — the original owner only matters for the payout.
        val sellerIsGuild = stall.owner.type == OwnerType.GUILD
        val proceedsGuildId = stall.owner.id // valid only when sellerIsGuild
        val now = Instant.now()
        val updated = stall.awardTo(
            OwnerRef.solo(buyer),
            offer.price,
            now,
            now.plus(RentTimingPolicy.collectionInterval(config)),
        )
        try {
            stalls.save(updated)
        } catch (failure: Exception) {
            return rejectAndRefundBuyer(stallId, buyer, total, failure)
        }
        try {
            offers.delete(stallId)
        } catch (failure: Exception) {
            log.severe(
                "SellOfferService.purchase: stall ${stallId.value} transferred to $buyer but " +
                    "the completed offer could not be removed. cause=${failure.message}",
            )
            alerter.alert(
                context = "sell-offer:cleanup",
                detail = "stall ${stallId.value} transferred to buyer $buyer but its completed offer remains",
                affected = buyer,
                amount = total,
            )
        }
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                StallStateChangedEvent(stallId.value, previousState, updated.state)
            )
        } catch (failure: Exception) {
            log.warning(
                "SellOfferService.purchase: failed to fire StallStateChangedEvent for " +
                    "${stallId.value}: ${failure.message}",
            )
        }

        // 2. Pay seller (or guild bank) + route tax. Failures here are
        // logged but don't roll back — the stall is already transferred.
        // The alternative (paying before transferring) risks double-pay
        // on retry. Tax destination of "system" routes nowhere.
        val proceedsPaid = if (sellerIsGuild) {
            guildProvider.bankDeposit(proceedsGuildId, offer.price)
        } else {
            economy.deposit(offer.sellerUuid, offer.price)
        }
        if (!proceedsPaid) {
            log.warning(
                "SellOfferService.purchase: proceeds deposit failed for " +
                    (if (sellerIsGuild) "guild $proceedsGuildId" else "seller ${offer.sellerUuid}") +
                    " (price=${offer.price}); stall transfer already committed."
            )
            alerter.alert(
                context = "sell-offer:proceeds",
                detail = "stall ${stallId.value} transferred to buyer $buyer but proceeds payout failed " +
                    (if (sellerIsGuild) "to guild $proceedsGuildId" else "to seller ${offer.sellerUuid}"),
                affected = if (sellerIsGuild) null else offer.sellerUuid,
                amount = offer.price,
            )
        }
        val taxDestination = parseTaxDestination(config.shop.taxDestination)
        if (taxDestination != null && tax > 0) {
            if (!economy.deposit(taxDestination, tax)) {
                log.warning(
                    "SellOfferService.purchase: tax deposit failed for $taxDestination " +
                        "(tax=$tax); stall transfer already committed."
                )
            }
        }

        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                SellOfferCompletedEvent(stallId.value, offer.sellerUuid, buyer, offer.price, tax)
            )
        } catch (failure: Exception) {
            log.warning(
                "SellOfferService.purchase: failed to fire SellOfferCompletedEvent for " +
                    "${stallId.value}: ${failure.message}",
            )
        }
        return Result.Purchased(offer, tax)
    }

    private fun rejectAndRefundBuyer(
        stallId: StallId,
        buyer: UUID,
        total: Long,
        failure: Exception,
    ): Result.Rejected {
        val refunded = try {
            economy.deposit(buyer, total)
        } catch (refundFailure: Exception) {
            log.severe(
                "SellOfferService.purchase: refund of $total to $buyer threw after stall " +
                    "${stallId.value} transfer failed: ${refundFailure.message}",
            )
            false
        }
        if (refunded) {
            log.warning(
                "SellOfferService.purchase: transfer failed for ${stallId.value}; " +
                    "buyer $buyer was refunded $total. cause=${failure.message}",
            )
            return Result.Rejected("The stall changed before the purchase completed. Your payment was refunded.")
        }
        alerter.alert(
            context = "sell-offer:buyer-refund",
            detail = "stall ${stallId.value} was not transferred and buyer $buyer could not be refunded",
            affected = buyer,
            amount = total,
        )
        return Result.Rejected("The purchase could not be completed. Staff have been alerted.")
    }

    /**
     * The tax destination is a UUID string in config; the literal
     * "system" (or any value that doesn't parse as a UUID) routes the
     * tax to a no-op sink — same compromise as the existing shop
     * trade flow.
     */
    private fun parseTaxDestination(raw: String): UUID? = try {
        UUID.fromString(raw.trim())
    } catch (_: IllegalArgumentException) {
        null
    }
}
