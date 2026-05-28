package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.auction.AuctionState
import net.badgersmc.em.domain.auction.Bid
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.annotations.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * Result of an auction lifecycle operation.
 */
sealed class AuctionResult {
    /** Operation completed successfully. */
    data class Success(val auction: Auction) : AuctionResult()
    /** Operation failed with a descriptive reason. */
    data class Failure(val reason: String) : AuctionResult()
    /** Referenced auction was not found. */
    data object NotFound : AuctionResult()
}

/**
 * Report from settling expired auctions.
 */
data class SettlementReport(
    val settled: Int,
    val errors: Int
)

/**
 * Outcome of [AuctionLifecycleService.startMassAuction]. Sealed so callers
 * can exhaustively match on the two variants without an `else` branch.
 */
sealed class MassAuctionResult {
    /** At least one stall was processed (created may still be 0 if all were skipped). */
    data class Report(
        val created: Int,
        val skipped: Int,
        val errors: Int,
        val auctionIds: List<AuctionId>
    ) : MassAuctionResult()

    /** Input validation rejected the entire operation before any stall was touched. */
    data class Invalid(val reason: String) : MassAuctionResult()
}

/**
 * Application-layer service managing the full auction lifecycle (REQ-007).
 *
 * Handles creation, bidding, cancellation, and settlement of expired auctions.
 */
@Service
class AuctionLifecycleService(
    private val auctionRepository: AuctionRepository,
    private val stallRepository: StallRepository,
    private val economy: EconomyProvider,
    private val config: EnthusiaMarketConfig,
    private val limits: LimitResolutionService,
    private val sellOffers: SellOfferRepository,
) {
    private val logger = Logger.getLogger(AuctionLifecycleService::class.java.name)

    /**
     * Create a new auction for a stall.
     *
     * @param stallId the stall to auction
     * @param playerUuid the player creating the auction (must be stall owner)
     * @param startingBid the minimum bid amount
     * @param durationStr optional ISO-8601 duration string (e.g. "PT24H"), null for default
     * @return [AuctionResult.Success] with the created auction, or [AuctionResult.Failure]
     */
    fun createAuction(
        stallId: StallId,
        playerUuid: UUID,
        startingBid: Long,
        durationStr: String?
    ): AuctionResult {
        val stall = stallRepository.findById(stallId)
            ?: return AuctionResult.Failure("Stall not found: ${stallId.value}")

        if (stall.owner != OwnerRef.solo(playerUuid)) {
            return AuctionResult.Failure("You are not the owner of this stall")
        }

        val existing = auctionRepository.findOpenByStall(stallId)
        if (existing != null) {
            return AuctionResult.Failure("An open auction already exists for this stall")
        }

        // REQ-263 — reverse mutex with the sell-offer flow. If the
        // stall is up for direct sale, refuse to wrap it in an
        // auction; the seller must cancel the offer first.
        if (sellOffers.findByStall(stallId) != null) {
            return AuctionResult.Failure("An open sell offer already exists for this stall")
        }

        val bidValidation = validateStartingBid(startingBid)
        if (bidValidation != null) return bidValidation

        val duration = resolveDuration(durationStr)
            ?: return AuctionResult.Failure("Duration resolution failed — this should not happen")

        val now = Instant.now()
        val auction = Auction(
            id = AuctionId(UUID.randomUUID().toString()),
            stallId = stallId,
            state = AuctionState.OPEN,
            startAt = now,
            endAt = now.plus(duration),
            startingBid = startingBid,
            highBid = null,
            antiSnipeWindow = Duration.ofSeconds(config.auction.antiSnipeSec.toLong())
        )

        auctionRepository.create(auction)
        return AuctionResult.Success(auction)
    }

    /**
     * Launch a system-initiated auction for every UNOWNED stall at once (REQ-028).
     *
     * Each created auction shares the same starting bid and end time. Stalls already
     * holding an open auction are skipped. Affected stalls transition to AUCTIONING.
     *
     * @param startingBid starting bid applied to every created auction
     * @param durationStr optional ISO-8601 duration string; null uses `auction.defaultDuration`
     * @return [MassAuctionResult.Report] with counts and the new auction ids, or
     *         [MassAuctionResult.Invalid] when inputs fail validation
     */
    fun startMassAuction(startingBid: Long, durationStr: String?): MassAuctionResult {
        validateStartingBid(startingBid)?.let { return MassAuctionResult.Invalid(it.reason) }
        val duration = resolveDuration(durationStr)
            ?: return MassAuctionResult.Invalid("Invalid auction duration: '$durationStr'")

        val now = Instant.now()
        val endAt = now.plus(duration)
        val antiSnipe = Duration.ofSeconds(config.auction.antiSnipeSec.toLong())

        val candidates = stallRepository.byState(StallState.UNOWNED)
        val created = mutableListOf<AuctionId>()
        var skipped = 0
        var errors = 0

        for (stall in candidates) {
            val result = startAuctionForStall(stall, now, endAt, antiSnipe, startingBid)
            when {
                result == null -> skipped++
                result.first != null -> created.add(result.first)
                else -> errors++
            }
        }

        return MassAuctionResult.Report(
            created = created.size,
            skipped = skipped,
            errors = errors,
            auctionIds = created
        )
    }

    /**
     * Process a single stall within a mass auction. Returns:
     * - `null` if the stall already has an open auction (skipped)
     * - `Pair(auctionId, "created")` on success
     * - `Pair(null, "error")` on failure
     */
    private fun startAuctionForStall(
        stall: Stall,
        now: Instant,
        endAt: Instant,
        antiSnipe: Duration,
        startingBid: Long
    ): Pair<AuctionId?, String>? {
        try {
            if (auctionRepository.findOpenByStall(stall.id) != null) {
                return null // skipped
            }
            val auction = Auction(
                id = AuctionId(UUID.randomUUID().toString()),
                stallId = stall.id,
                state = AuctionState.OPEN,
                startAt = now,
                endAt = endAt,
                startingBid = startingBid,
                highBid = null,
                antiSnipeWindow = antiSnipe
            )
            // Persist auction first, then transition stall. If the stall save
            // fails we compensate by closing the just-created auction so we
            // never leave an OPEN auction pointing at a non-AUCTIONING stall.
            auctionRepository.create(auction)
            try {
                stallRepository.save(stall.copy(state = StallState.AUCTIONING))
            } catch (stallErr: RuntimeException) {
                try {
                    auctionRepository.save(auction.close())
                } catch (compErr: RuntimeException) {
                    logger.warning(
                        "startAuctionForStall: failed to compensate auction ${auction.id} " +
                            "after stall save failed for ${stall.id}: ${compErr.message}"
                    )
                }
                throw stallErr
            }
            return Pair(auction.id, "created")
        } catch (e: RuntimeException) {
            logger.warning("startAuctionForStall: stall ${stall.id} failed — ${e.message}")
            return Pair(null, "error")
        }
    }

    private fun validateStartingBid(startingBid: Long): AuctionResult.Failure? {
        if (startingBid < config.auction.minStartingBid) {
            return AuctionResult.Failure("Starting bid must be at least ${config.auction.minStartingBid}")
        }
        return null
    }

    private fun resolveDuration(durationStr: String?): Duration? {
        val minDuration = Duration.parse(config.auction.minDuration)
        val maxDuration = Duration.parse(config.auction.maxDuration)
        val duration = if (durationStr != null) {
            try { Duration.parse(durationStr) } catch (e: Exception) { return null }
        } else {
            Duration.parse(config.auction.defaultDuration)
        }
        if (duration < minDuration || duration > maxDuration) return null
        return duration
    }

    /**
     * Place a bid on an open auction.
     *
     * @param auctionId the auction to bid on
     * @param playerUuid the bidder
     * @param amount the bid amount
     * @return [AuctionResult.Success] with the updated auction, [AuctionResult.Failure],
     *         or [AuctionResult.NotFound]
     */
    fun placeBid(auctionId: AuctionId, playerUuid: UUID, amount: Long): AuctionResult {
        val auction = auctionRepository.findById(auctionId)
            ?: return AuctionResult.NotFound

        if (auction.state != AuctionState.OPEN) {
            return AuctionResult.Failure("Auction is not open")
        }

        return try {
            val updated = auction.placeBid(playerUuid, amount, Instant.now())
            auctionRepository.save(updated)
            AuctionResult.Success(updated)
        } catch (e: IllegalArgumentException) {
            AuctionResult.Failure(e.message ?: "Bid rejected")
        } catch (e: IllegalStateException) {
            AuctionResult.Failure(e.message ?: "Bid rejected")
        }
    }

    /**
     * Cancel an open auction. Only the stall owner may cancel.
     *
     * @param auctionId the auction to cancel
     * @param playerUuid the player requesting cancellation
     * @return [AuctionResult.Success] with the closed auction, or [AuctionResult.Failure]/[AuctionResult.NotFound]
     */
    fun cancelAuction(auctionId: AuctionId, playerUuid: UUID): AuctionResult {
        val auction = auctionRepository.findById(auctionId)
            ?: return AuctionResult.NotFound

        val stall = stallRepository.findById(auction.stallId)
            ?: return AuctionResult.Failure("Stall not found for auction")

        if (stall.owner != OwnerRef.solo(playerUuid)) {
            return AuctionResult.Failure("Only the stall owner can cancel this auction")
        }

        val closed = auction.close()
        auctionRepository.save(closed)
        return AuctionResult.Success(closed)
    }

    /**
     * Settle all expired auctions.
     *
     * For each expired auction:
     * - If there is a high bid, award the stall to the winner, pay the seller (minus fee),
     *   and mark the auction as closed.
     * - If there are no bids, just close the auction.
     *
     * @return [SettlementReport] with counts of settled and errored auctions
     */
    fun settleExpired(): SettlementReport {
        val expired = auctionRepository.findExpired()
        var settled = 0
        var errors = 0

        for (auction in expired) {
            try {
                if (auction.highBid != null) {
                    settleWithWinner(auction)
                } else {
                    // No bids. If this was a system-initiated mass auction
                    // (UNOWNED stall transitioned to AUCTIONING), revert the
                    // stall BEFORE closing the auction. If we closed the
                    // auction first and the stall save then failed, the
                    // auction would no longer appear in findExpired() and the
                    // stall would stay stuck in AUCTIONING forever.
                    val stall = stallRepository.findById(auction.stallId)
                    if (stall != null
                        && stall.owner.type == net.badgersmc.em.domain.stall.OwnerType.NONE
                        && stall.state == StallState.AUCTIONING
                    ) {
                        stallRepository.save(stall.copy(state = StallState.UNOWNED))
                    }
                    auctionRepository.save(auction.close())
                }
                settled++
            } catch (e: Exception) {
                errors++
            }
        }

        return SettlementReport(settled = settled, errors = errors)
    }

    private fun settleWithWinner(auction: Auction) {
        val bid = auction.highBid ?: return
        val stall = stallRepository.findById(auction.stallId)
            ?: throw IllegalStateException("Stall not found for auction ${auction.id}")

        // REQ-212 — limit gate. Runs BEFORE economy.withdraw so a winner
        // at their cap is never charged. Region kind is currently a
        // placeholder: Stall doesn't carry a kind field yet (lands with
        // TDD-220), and the per-kind cap collapses to a no-op until then
        // — the total cap still applies and is the typical ARM use case.
        val winnerOwnedCount = stallRepository.all().count {
            it.owner.type == net.badgersmc.em.domain.stall.OwnerType.SOLO &&
                it.owner.id == bid.bidder.toString()
        }
        val decision = limits.canClaim(
            player = bid.bidder,
            kind = DEFAULT_KIND,
            currentTotal = winnerOwnedCount,
            currentForKind = winnerOwnedCount,
        )
        if (decision is LimitResolutionService.ClaimDecision.Rejected) {
            // Treat as no-bid: revert system-mass-auctioned stall to
            // UNOWNED, close the auction so it stops appearing in
            // findExpired(). Winner is never charged. A future event
            // hook can notify the winner; for now, log + drop.
            logger.info(
                "Auction ${auction.id} winner ${bid.bidder} over limit " +
                    "($decision); reverting without payment."
            )
            if (stall.state == StallState.AUCTIONING &&
                stall.owner.type == net.badgersmc.em.domain.stall.OwnerType.NONE
            ) {
                stallRepository.save(stall.copy(state = StallState.UNOWNED))
            }
            auctionRepository.save(auction.close())
            return
        }

        // 0. Withdraw from winner FIRST (before any state changes)
        if (!economy.withdraw(bid.bidder, bid.amount)) {
            throw IllegalStateException("Failed to withdraw winning bid from ${bid.bidder}")
        }

        // 1. Persist state changes (stall awarded + auction closed)
        // If this fails, the caller will retry — we'll need to refund the winner manually.
        // The alternative of persisting after payment risks double-paying on retry.
        val awardAt = Instant.now()
        val updatedStall = stall.awardTo(OwnerRef.solo(bid.bidder), bid.amount, awardAt)
        stallRepository.save(updatedStall)
        auctionRepository.save(auction.close())

        // 2. Pay seller (after state is persisted — if this fails, seller funds are still held)
        // Trade-off: if deposit fails, the winner has been charged but the seller hasn't been paid.
        // The auction is already closed so it won't retry. Seller proceeds can be resolved manually.
        // This is preferable to the reverse (paying seller twice on retry).
        val feePct = config.auction.feePct
        val feeAmount = (bid.amount * feePct).toLong()
        val sellerProceeds = bid.amount - feeAmount
        val sellerUuid = extractOwnerUuid(stall)
        if (sellerUuid == null || !economy.deposit(sellerUuid, sellerProceeds)) {
            logger.warning(
                "Auction ${auction.id}: seller payment failed. " +
                    "Winner charged ${bid.amount}, seller proceeds $sellerProceeds pending."
            )
        }
    }

    /**
     * Extract the player UUID from the stall's owner ref.
     * Returns null for non-SOLO owner types (guild owners cannot auction).
     */
    private fun extractOwnerUuid(stall: net.badgersmc.em.domain.stall.Stall): UUID? {
        return if (stall.owner.type == net.badgersmc.em.domain.stall.OwnerType.SOLO) {
            try {
                UUID.fromString(stall.owner.id)
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            null
        }
    }

    private companion object {
        // Placeholder region kind for limit checks until Stall gains
        // an explicit kind field (TDD-220). Per-kind caps under this
        // name resolve via config.limits.<group>.regionkinds.default.
        const val DEFAULT_KIND = "default"
    }
}