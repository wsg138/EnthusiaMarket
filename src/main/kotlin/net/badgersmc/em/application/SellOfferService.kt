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
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.events.SellOfferCompletedEvent
import net.badgersmc.em.events.SellOfferCreatedEvent
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/** Orchestrates the ARM-style sell-offer flow (REQ-260..264). */
@Service
@Suppress("LongParameterList")
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
    private val shops: ShopRepository,
    private val regionMembers: RegionMemberSync,
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

    private data class PurchaseContext(
        val offer: SellOffer,
        val stall: Stall,
        val tax: Long,
        val total: Long,
    )

    private sealed interface PurchaseValidation {
        data class Ready(val context: PurchaseContext) : PurchaseValidation
        data class Rejected(val result: Result) : PurchaseValidation
    }

    fun create(stallId: StallId, seller: UUID, price: Long): Result {
        if (price <= 0) return Result.Rejected("Price must be positive")
        val stall = stalls.findById(stallId) ?: return Result.NotFound
        if (!stall.canManage(seller, guildProvider)) return Result.NotAuthorised
        if (offers.findByStall(stallId) != null) return Result.OfferOpen
        if (auctions.findOpenByStall(stallId) != null) return Result.AuctionOpen

        val offer = SellOffer(stallId, seller, price, Instant.now())
        offers.save(offer)
        Bukkit.getServer()?.pluginManager?.callEvent(SellOfferCreatedEvent(stallId.value, seller, price))
        return Result.Created(offer)
    }

    fun cancel(stallId: StallId, actor: UUID): Result {
        val offer = offers.findByStall(stallId) ?: return Result.NotFound
        if (offer.sellerUuid != actor) {
            val stall = stalls.findById(stallId)
            if (stall == null || !stall.canManage(actor, guildProvider)) return Result.NotAuthorised
        }
        offers.delete(stallId)
        return Result.Cancelled(offer)
    }

    fun purchase(stallId: StallId, buyer: UUID): Result = try {
        moderationPolicy.withAcquisitionPermit(buyer) { purchaseWithPermit(stallId, buyer) }
    } catch (blocked: MarketAcquisitionBlockedException) {
        Result.Rejected(blocked.message ?: "Market acquisitions are restricted")
    }

    private fun purchaseWithPermit(stallId: StallId, buyer: UUID): Result {
        val validation = validatePurchase(stallId, buyer)
        if (validation is PurchaseValidation.Rejected) return validation.result
        val context = (validation as PurchaseValidation.Ready).context
        if (!economy.withdraw(buyer, context.total)) {
            return Result.Rejected("Insufficient funds: ${context.total} required")
        }

        val updated = try {
            persistOwnershipTransfer(context, buyer)
        } catch (failure: Exception) {
            return rejectAndRefundBuyer(stallId, buyer, context.total, failure)
        }
        cleanupPreviousOwnership(context, buyer)
        cleanupCompletedOffer(stallId, buyer, context.total)
        publishStateChange(stallId, context.stall.state, updated.state)
        paySeller(context, buyer)
        payTax(context.tax)
        publishCompleted(context, buyer)
        return Result.Purchased(context.offer, context.tax)
    }

    private fun validatePurchase(stallId: StallId, buyer: UUID): PurchaseValidation {
        if (mutationGate.isStallLocked(stallId.value)) {
            return rejectedPurchase("This stall is temporarily unavailable")
        }
        val (offer, stall) = loadPurchaseInputs(stallId)
            ?: return PurchaseValidation.Rejected(Result.NotFound)
        purchaseRejection(offer, stall, buyer)?.let { return rejectedPurchase(it) }
        val taxPct = config.shop.taxPct
        if (taxPct !in 0.0..1.0) return rejectedPurchase("Invalid tax percentage: $taxPct")
        ownershipLimitRejection(buyer, stall)?.let { return rejectedPurchase(it) }
        val tax = (offer.price * taxPct).toLong()
        return PurchaseValidation.Ready(PurchaseContext(offer, stall, tax, offer.price + tax))
    }

    private fun loadPurchaseInputs(stallId: StallId): Pair<SellOffer, Stall>? {
        val offer = offers.findByStall(stallId) ?: return null
        val stall = stalls.findById(stallId) ?: return null
        return offer to stall
    }

    private fun rejectedPurchase(reason: String): PurchaseValidation.Rejected =
        PurchaseValidation.Rejected(Result.Rejected(reason))

    private fun purchaseRejection(offer: SellOffer, stall: Stall, buyer: UUID): String? = when {
        buyer == offer.sellerUuid -> "You cannot buy your own stall"
        !stall.canManage(offer.sellerUuid, guildProvider) -> "This sell offer is no longer valid"
        else -> null
    }

    private fun ownershipLimitRejection(buyer: UUID, stall: Stall): String? {
        val counts = ownership.counts(buyer)
        return when (val decision = limits.canClaim(buyer, stall.kind, counts.total, counts.byKind[stall.kind] ?: 0)) {
            is LimitResolutionService.ClaimDecision.Rejected.TotalCapReached ->
                "Stall limit reached (${decision.cap})"
            is LimitResolutionService.ClaimDecision.Rejected.KindCapReached ->
                "Limit reached for ${decision.kind} stalls (${decision.cap})"
            LimitResolutionService.ClaimDecision.Allowed -> null
        }
    }

    private fun persistOwnershipTransfer(context: PurchaseContext, buyer: UUID): Stall {
        val now = Instant.now()
        val updated = context.stall.awardTo(
            OwnerRef.solo(buyer),
            context.offer.price,
            now,
            now.plus(RentTimingPolicy.collectionInterval(config)),
        )
        stalls.save(updated)
        return updated
    }

    private fun cleanupPreviousOwnership(context: PurchaseContext, buyer: UUID) {
        try {
            for (shop in shops.findByStall(context.stall.id.value)) {
                if (shop.adminShop) continue
                try {
                    shops.delete(shop.id)
                } catch (failure: Exception) {
                    log.warning(
                        "SellOfferService.purchase: failed to remove previous shop ${shop.id} from " +
                            "stall ${context.stall.id.value} after transfer to $buyer. cause=${failure.message}",
                    )
                    alerter.alert(
                        context = "sell-offer:shop-cleanup",
                        detail = "stall ${context.stall.id.value} transferred to $buyer but shop ${shop.id} remains",
                        affected = buyer,
                        amount = context.offer.price,
                    )
                }
            }
        } catch (failure: Exception) {
            log.warning(
                "SellOfferService.purchase: failed to enumerate previous shops for stall " +
                    "${context.stall.id.value} after transfer to $buyer. cause=${failure.message}",
            )
            alerter.alert(
                context = "sell-offer:shop-cleanup",
                detail = "stall ${context.stall.id.value} transferred to $buyer but shop cleanup could not be completed",
                affected = buyer,
                amount = context.offer.price,
            )
        }

        try {
            regionMembers.setOwner(context.stall.world, context.stall.regionId, buyer)
        } catch (failure: Exception) {
            log.warning(
                "SellOfferService.purchase: failed to sync region ownership for stall " +
                    "${context.stall.id.value} after transfer to $buyer. cause=${failure.message}",
            )
            alerter.alert(
                context = "sell-offer:region-sync",
                detail = "stall ${context.stall.id.value} transferred to $buyer but region ownership sync failed",
                affected = buyer,
                amount = context.offer.price,
            )
        }
    }

    private fun cleanupCompletedOffer(stallId: StallId, buyer: UUID, total: Long) {
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
    }

    private fun publishStateChange(stallId: StallId, previousState: StallState, currentState: StallState) {
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                StallStateChangedEvent(stallId.value, previousState, currentState),
            )
        } catch (failure: Exception) {
            log.warning("SellOfferService.purchase: failed to fire StallStateChangedEvent for ${stallId.value}: ${failure.message}")
        }
    }

    private fun paySeller(context: PurchaseContext, buyer: UUID) {
        val sellerIsGuild = context.stall.owner.type == OwnerType.GUILD
        val recipient = sellerRecipient(context, sellerIsGuild)
        if (depositSeller(context, sellerIsGuild)) return
        log.warning(
            "SellOfferService.purchase: proceeds deposit failed for $recipient " +
                "(price=${context.offer.price}); stall transfer already committed.",
        )
        alerter.alert(
            context = "sell-offer:proceeds",
            detail = "stall ${context.stall.id.value} transferred to buyer $buyer but proceeds payout failed to $recipient",
            affected = if (sellerIsGuild) null else context.offer.sellerUuid,
            amount = context.offer.price,
        )
    }

    private fun depositSeller(context: PurchaseContext, sellerIsGuild: Boolean): Boolean =
        if (sellerIsGuild) {
            guildProvider.bankDeposit(context.stall.owner.id, context.offer.price)
        } else {
            economy.deposit(context.offer.sellerUuid, context.offer.price)
        }

    private fun sellerRecipient(context: PurchaseContext, sellerIsGuild: Boolean): String =
        if (sellerIsGuild) "guild ${context.stall.owner.id}" else "seller ${context.offer.sellerUuid}"

    private fun payTax(tax: Long) {
        val destination = parseTaxDestination(config.shop.taxDestination) ?: return
        if (tax > 0 && !economy.deposit(destination, tax)) {
            log.warning("SellOfferService.purchase: tax deposit failed for $destination (tax=$tax); stall transfer already committed.")
        }
    }

    private fun publishCompleted(context: PurchaseContext, buyer: UUID) {
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                SellOfferCompletedEvent(
                    context.stall.id.value,
                    context.offer.sellerUuid,
                    buyer,
                    context.offer.price,
                    context.tax,
                ),
            )
        } catch (failure: Exception) {
            log.warning(
                "SellOfferService.purchase: failed to fire SellOfferCompletedEvent for " +
                    "${context.stall.id.value}: ${failure.message}",
            )
        }
    }

    private fun rejectAndRefundBuyer(
        stallId: StallId,
        buyer: UUID,
        total: Long,
        failure: Exception,
    ): Result.Rejected {
        if (refundBuyer(stallId, buyer, total)) {
            log.warning(
                "SellOfferService.purchase: transfer failed for ${stallId.value}; " +
                    "buyer $buyer was refunded $total. cause=${failure.message}",
            )
            return Result.Rejected("The stall changed before the purchase completed. Your payment was refunded.")
        }
        alertRefundFailure(stallId, buyer, total)
        return Result.Rejected("The purchase could not be completed. Staff have been alerted.")
    }

    private fun refundBuyer(stallId: StallId, buyer: UUID, total: Long): Boolean = try {
        economy.deposit(buyer, total)
    } catch (refundFailure: Exception) {
        log.severe(
            "SellOfferService.purchase: refund of $total to $buyer threw after stall " +
                "${stallId.value} transfer failed: ${refundFailure.message}",
        )
        false
    }

    private fun alertRefundFailure(stallId: StallId, buyer: UUID, total: Long) {
        alerter.alert(
            context = "sell-offer:buyer-refund",
            detail = "stall ${stallId.value} was not transferred and buyer $buyer could not be refunded",
            affected = buyer,
            amount = total,
        )
    }

    private fun parseTaxDestination(raw: String): UUID? = try {
        UUID.fromString(raw.trim())
    } catch (_: IllegalArgumentException) {
        null
    }
}
