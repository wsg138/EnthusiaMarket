package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.auction.AuctionState
import net.badgersmc.em.domain.auction.Bid
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.MarketAcquisitionBlockedException
import net.badgersmc.em.domain.ports.MarketModerationPolicy
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.annotations.Service
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
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
@Suppress("TooManyFunctions", "LongParameterList")
class AuctionLifecycleService(
    private val auctionRepository: AuctionRepository,
    private val stallRepository: StallRepository,
    private val economy: EconomyProvider,
    private val config: EnthusiaMarketConfig,
    private val limits: LimitResolutionService,
    private val sellOffers: SellOfferRepository,
    private val regionMembers: net.badgersmc.em.domain.ports.RegionMemberSync,
    private val ownership: StallOwnershipCounter,
    private val ipLimiter: IpLimiter,
    private val schematics: net.badgersmc.em.domain.ports.SchematicService =
        net.badgersmc.em.domain.ports.SchematicService.Disabled,
    private val lang: LangService,
    private val moderationPolicy: MarketModerationPolicy = MarketModerationPolicy.AllowAll,
) {
    private val logger = Logger.getLogger(AuctionLifecycleService::class.java.name)
    private val auctioningStates = setOf(
        StallState.AUCTIONING,
        StallState.RE_AUCTIONING,
        StallState.EMERGENCY_AUCTIONING,
    )

    /** Injectable clock for deterministic time-travel in tests. */
    internal var clock: Clock = Clock.systemUTC()

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

        val now = clock.instant()
        val auction = Auction(
            id = AuctionId(UUID.randomUUID().toString()),
            stallId = stallId,
            state = AuctionState.OPEN,
            startAt = now,
            endAt = now.plus(duration),
            startingBid = startingBid,
            highBid = null,
            antiSnipeWindow = config.auction.antiSnipeWindowDuration,
            antiSnipeExtension = config.auction.antiSnipeExtensionDuration
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

        val now = clock.instant()
        val endAt = now.plus(duration)
        val antiSnipe = config.auction.antiSnipeWindowDuration
        val antiSnipeExtend = config.auction.antiSnipeExtensionDuration

        val candidates = stallRepository.byState(StallState.UNOWNED)
        val created = mutableListOf<AuctionId>()
        var skipped = 0
        var errors = 0

        for (stall in candidates) {
            val result = startAuctionForStall(stall, now, endAt, antiSnipe, antiSnipeExtend, startingBid)
            val id = result?.first
            when {
                result == null -> skipped++
                id != null -> created.add(id)
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

    @Suppress("TooGenericExceptionCaught")
    private fun startAuctionForStall(
        stall: Stall,
        now: Instant,
        endAt: Instant,
        antiSnipe: Duration,
        antiSnipeExtend: Duration,
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
                antiSnipeWindow = antiSnipe,
                antiSnipeExtension = antiSnipeExtend
            )
            // Persist auction first, then transition stall. If the stall save
            // fails we compensate by closing the just-created auction so we
            // never leave an OPEN auction pointing at a non-AUCTIONING stall.
            auctionRepository.create(auction)
            try {
                stallRepository.save(stall.copy(state = StallState.AUCTIONING))
                fireStateChanged(stall.id.value, stall.state, StallState.AUCTIONING)
            } catch (stallErr: Exception) {
                try {
                    auctionRepository.save(auction.close())
                } catch (compErr: Exception) {
                    logger.warning(
                        "startAuctionForStall: failed to compensate auction ${auction.id} " +
                            "after stall save failed for ${stall.id}: ${compErr.message}"
                    )
                }
                throw stallErr
            }
            return Pair(auction.id, "created")
        } catch (e: Exception) {
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
     * @param ip the bidder's IP address for rate limiting
     * @return [AuctionResult.Success] with the updated auction, [AuctionResult.Failure],
     *         or [AuctionResult.NotFound]
     */
    fun placeBid(auctionId: AuctionId, playerUuid: UUID, amount: Long, ip: String): AuctionResult {
        return try {
            moderationPolicy.withAcquisitionPermit(playerUuid) {
                placeBidWithPermit(auctionId, playerUuid, amount, ip)
            }
        } catch (blocked: MarketAcquisitionBlockedException) {
            AuctionResult.Failure(blocked.message ?: "Market acquisitions are restricted")
        }
    }

    private fun placeBidWithPermit(auctionId: AuctionId, playerUuid: UUID, amount: Long, ip: String): AuctionResult {
        val auction = findAuction(auctionId) ?: return AuctionResult.NotFound

        if (auction.state != AuctionState.OPEN) {
            return AuctionResult.Failure("Auction is not open")
        }

        val reservation = ipLimiter.acquireAuction(ip, auction.id.value)
        if (!reservation.allowed) {
            return AuctionResult.Failure("You already have an active bid on another auction.")
        }
        var completed = false
        try {
            val attempt = attemptBid(auction, playerUuid, amount)
            if (attempt.failure != null) return attempt.failure
            val updated = requireNotNull(attempt.auction)
            val result = finalizeBid(auction, updated, playerUuid, amount)
            completed = result is AuctionResult.Success
            return result
        } finally {
            if (!completed) ipLimiter.rollback(reservation.reservation)
        }
    }

    /**
     * Orchestrate the post-validation phases of a bid: compute the delta,
     * charge the bidder, persist, and refund the previous high bidder.
     * Extracted from [placeBid] to stay under detekt limits.
     */
    private fun finalizeBid(
        original: Auction,
        updated: Auction,
        playerUuid: UUID,
        amount: Long,
    ): AuctionResult {
        val previousBid = original.highBid
        val charge = computeCharge(previousBid, playerUuid, amount)
            ?: return AuctionResult.Failure("Bid must exceed current high bid")

        if (!economy.withdraw(playerUuid, charge)) {
            return AuctionResult.Failure("Could not withdraw $charge. Check your balance.")
        }

        persistBidWithRollback(playerUuid, charge, updated, original.id)?.let { return it }
        val newBidderName = runCatching { Bukkit.getPlayer(playerUuid) }.getOrNull()?.name ?: "Unknown"
        refundPreviousBidderIfOutbid(previousBid, playerUuid, original.id, original.stallId, amount, newBidderName)
        return AuctionResult.Success(updated)
    }

    /**
     * Compute the amount to charge the bidder. Returns `null` if the bid
     * would not increase the current high bid (bid too low or rebid at
     * the same level).
     */
    private fun computeCharge(
        previousBid: Bid?,
        playerUuid: UUID,
        amount: Long,
    ): Long? {
        val charge = if (previousBid?.bidder == playerUuid) amount - previousBid.amount else amount
        return charge.takeIf { it > 0L }
    }

    /**
     * Look up an auction by ID, falling back to stall-ID match.
     * Extracted from [placeBid] to keep complexity within Lizard limits.
     */
    private fun findAuction(auctionId: AuctionId): Auction? {
        return auctionRepository.findById(auctionId)
            ?: auctionRepository.findOpenByStall(StallId(auctionId.value))
    }

    /**
     * Persist the updated auction and roll back the charge on failure.
     * Returns `null` on success, or a [AuctionResult.Failure] that the
     * caller should propagate.
     */
    private fun persistBidWithRollback(
        playerUuid: UUID,
        charge: Long,
        updated: Auction,
        auctionId: AuctionId,
    ): AuctionResult.Failure? {
        try {
            auctionRepository.save(updated)
            return null
        } catch (e: Exception) {
            refundOrLog(playerUuid, charge, "placeBid rollback after auction save failed for $auctionId")
            return AuctionResult.Failure(e.message ?: "Bid rejected")
        }
    }

    /**
     * Refund the previous high bidder if they were outbid by a different
     * player.
     */
    private fun refundPreviousBidderIfOutbid(
        previousBid: Bid?,
        playerUuid: UUID,
        auctionId: AuctionId,
        stallId: StallId,
        newAmount: Long,
        newBidderName: String,
    ) {
        if (previousBid != null && previousBid.bidder != playerUuid) {
            refundOrLog(
                previousBid.bidder,
                previousBid.amount,
                "previous high-bidder refund after outbid on auction $auctionId",
            )
            runCatching { Bukkit.getPlayer(previousBid.bidder) }?.getOrNull()?.sendMessage(
                lang.msg("auction.outbid", "stall" to stallId.value, "amount" to newAmount, "bidder" to newBidderName)
            )
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

        // Gate: only the stall owner (SOLO) or an admin cancelling a
        // system auction (NONE) may cancel. GUILD auctions are not
        // cancellable via this path — the caller must be the owner.
        when (stall.owner.type) {
            OwnerType.SOLO -> if (stall.owner.id != playerUuid.toString()) {
                return AuctionResult.Failure("Only the stall owner can cancel this auction")
            }
            OwnerType.GUILD -> return AuctionResult.Failure(
                "Guild-owned auctions cannot be cancelled this way"
            )
            OwnerType.NONE -> { /* system auction — command layer enforces admin */ }
        }

        val closed = auction.close()
        auctionRepository.save(closed)
        ipLimiter.releaseAuctionBindings(auction.id.value)
        auction.highBid?.let {
            refundOrLog(it.bidder, it.amount, "cancelAuction refund for auction ${auction.id}")
        }
        // Revert a system-mass-auctioned stall (AUCTIONING + no owner) back to
        // UNOWNED so the sign becomes buyable again after cancellation.
        if (stall.state == StallState.AUCTIONING &&
            stall.owner.type == net.badgersmc.em.domain.stall.OwnerType.NONE
        ) {
            stallRepository.save(stall.copy(state = StallState.UNOWNED))
            fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
        }
        return AuctionResult.Success(closed)
    }

    /**
     * Extend an open auction's end time by the given duration.
     *
     * @param auctionId the auction to extend
     * @param extensionStr ISO-8601 duration string (e.g. "PT6H", "P1D")
     * @return [AuctionResult.Success] with the updated auction, or [AuctionResult.Failure]/[AuctionResult.NotFound]
     */
    fun extendAuction(auctionId: AuctionId, extensionStr: String): AuctionResult {
        val auction = auctionRepository.findById(auctionId)
            ?: auctionRepository.findOpenByStall(StallId(auctionId.value))
            ?: return AuctionResult.NotFound

        if (auction.state != AuctionState.OPEN) {
            return AuctionResult.Failure("Only open auctions can be extended")
        }

        val extension = try {
            Duration.parse(extensionStr)
        } catch (e: Exception) {
            return AuctionResult.Failure("Invalid duration format: '$extensionStr'. Use ISO-8601 (e.g. PT6H, P1D)")
        }

        if (extension.isNegative || extension.isZero) {
            return AuctionResult.Failure("Extension must be a positive duration")
        }

        val newEndAt = auction.endAt.plus(extension)
        val maxEnd = clock.instant().plus(Duration.parse(config.auction.maxDuration))
        if (newEndAt.isAfter(maxEnd)) {
            return AuctionResult.Failure(
                "Extension would exceed maximum auction duration (${config.auction.maxDuration} from now)"
            )
        }

        val extended = auction.copy(endAt = newEndAt)
        auctionRepository.save(extended)
        return AuctionResult.Success(extended)
    }

    /**
     * Clear stale high-bid data from all CANCELLED and CLOSED auctions for a stall.
     *
     * This surgically releases bidder state that can interfere with new auctions on the
     * same stall (e.g. IP limiter bindings, "already has active bid" checks). Only
     * touches non-OPEN auctions — running auctions are never affected.
     *
     * @param stallId the stall whose stale auctions should be cleared
     * @return number of auctions whose bid data was cleared
     */
    fun clearStaleBidData(stallId: StallId): Int {
        val auctions = auctionRepository.findByStall(stallId)
        var cleared = 0
        for (auction in auctions) {
            if (auction.state == AuctionState.OPEN) continue
            if (auction.highBid == null) continue
            val clearedAuction = auction.copy(highBid = null)
            auctionRepository.save(clearedAuction)
            cleared++
        }
        return cleared
    }

    /**
     * Emergency mass-cancel all open auctions and refund any held high bids.
     *
     * Errors for individual auctions are logged and counted but do not abort
     * the batch.
     *
     * @return number of auctions cancelled
     */
    fun cancelAllAuctions(): Int {
        val open = auctionRepository.allOpen()
        var count = 0
        var errors = 0
        for (auction in open) {
            if (cancelOneAuction(auction)) count++ else errors++
        }
        if (errors > 0) logger.warning("cancelAllAuctions: $errors error(s) during batch cancel")
        return count
    }

    private fun cancelOneAuction(auction: Auction): Boolean {
        return try {
            val cancelled = auction.copy(state = AuctionState.CANCELLED)
            auctionRepository.save(cancelled)
            auction.highBid?.let {
                refundOrLog(it.bidder, it.amount, "cancelAllAuctions refund for auction ${auction.id}")
            }
            ipLimiter.releaseAuctionBindings(auction.id.value)
            revertSystemAuctionedStall(auction)
            true
        } catch (e: Exception) {
            logger.warning("cancelAllAuctions: failed to cancel auction ${auction.id}: ${e.message}")
            false
        }
    }

    private fun revertSystemAuctionedStall(auction: Auction) {
        val stall = stallRepository.findById(auction.stallId)
        if (stall != null && systemAuctioned(stall)) {
            stallRepository.save(stall.copy(state = StallState.UNOWNED))
            fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
        }
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
                settleOne(auction)
                settled++
                ipLimiter.releaseAuctionBindings(auction.id.value)
            } catch (e: Exception) {
                errors++
            }
        }

        return SettlementReport(settled = settled, errors = errors)
    }

    private fun settleOne(auction: Auction) {
        if (auction.highBid != null) {
            settleWithWinner(auction)
        } else {
            closeExpiredWithoutBid(auction)
        }
    }

    private fun closeExpiredWithoutBid(auction: Auction) {
        val stall = stallRepository.findById(auction.stallId)
        if (stall != null && systemAuctioned(stall)) {
            stallRepository.save(stall.copy(state = StallState.UNOWNED))
            cleanupSellOffer(auction.stallId)
            fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
        }
        auctionRepository.save(auction.close())
    }

    private fun cleanupSellOffer(stallId: StallId) {
        if (sellOffers.findByStall(stallId) == null) return
        try {
            sellOffers.delete(stallId)
        } catch (cleanupError: Exception) {
            logger.warning(
                "AuctionLifecycleService: failed to cleanup lingering sell offer for " +
                    "${stallId.value}. cause=${cleanupError.message}"
            )
        }
    }

    private fun systemAuctioned(stall: Stall): Boolean {
        return stall.owner.type == OwnerType.NONE && stall.state in auctioningStates
    }

    private fun settleWithWinner(auction: Auction) {
        val bid = auction.highBid ?: return
        try {
            moderationPolicy.withAcquisitionPermit(bid.bidder) {
                settleWithWinnerWithPermit(auction)
            }
        } catch (blocked: MarketAcquisitionBlockedException) {
            val stall = stallRepository.findById(auction.stallId)
                ?: throw IllegalStateException("Stall not found for auction ${auction.id}")
            logger.info("Auction ${auction.id} winner is restricted; refunding without an ownership award")
            closeWithoutAward(auction, stall)
            refundOrLog(bid.bidder, bid.amount, "market restriction refund for auction ${auction.id}")
        }
    }

    private fun attemptBid(auction: Auction, player: UUID, amount: Long): BidAttempt {
        return try {
            BidAttempt(auction.placeBid(player, amount, clock.instant()), null)
        } catch (failure: IllegalArgumentException) {
            BidAttempt(null, AuctionResult.Failure(failure.message ?: "Bid rejected"))
        } catch (failure: IllegalStateException) {
            BidAttempt(null, AuctionResult.Failure(failure.message ?: "Bid rejected"))
        }
    }

    private data class BidAttempt(
        val auction: Auction?,
        val failure: AuctionResult.Failure?,
    )

    private fun winnerOverLimit(auction: Auction, stall: Stall, bid: Bid): Boolean {
        val counts = ownership.counts(bid.bidder)
        val decision = limits.canClaim(
            player = bid.bidder,
            kind = stall.kind,
            currentTotal = counts.total,
            currentForKind = counts.byKind[stall.kind] ?: 0,
        )
        if (decision !is LimitResolutionService.ClaimDecision.Rejected) return false
        logger.info(
            "Auction ${auction.id} winner ${bid.bidder} over limit " +
                "($decision); refunding and reverting without award."
        )
        closeWithoutAward(auction, stall)
        refundOrLog(bid.bidder, bid.amount, "limit rejection refund for auction ${auction.id}")
        return true
    }

    private fun captureFailed(auction: Auction, stall: Stall, bid: Bid): Boolean {
        if (!config.schematics.enabled) return false
        val result = schematics.capture(stall.id.value, stall.world, stall.regionId)
        if (result !is net.badgersmc.em.domain.ports.SchematicService.Result.Failure) return false
        logger.warning(
            "settleWithWinner: schematic capture failed for stall ${stall.id.value}; " +
                "aborting award and refunding ${bid.bidder}. cause=${result.cause.message}"
        )
        closeWithoutAward(auction, stall)
        refundOrLog(bid.bidder, bid.amount, "schematic failure refund for auction ${auction.id}")
        fireCaptureFailed(stall.id.value, stall.world, stall.regionId, result.cause)
        return true
    }

    private fun settleWithWinnerWithPermit(auction: Auction) {
        val bid = auction.highBid ?: return
        val stall = stallRepository.findById(auction.stallId)
            ?: throw IllegalStateException("Stall not found for auction ${auction.id}")
        if (winnerOverLimit(auction, stall, bid)) return

        // Winner already paid at bid time. Settlement must not charge again.

        // 0.5 REQ-270/273/274 — snapshot the stall geometry BEFORE finalising
        // ownership. On failure: log, refund the winner, abort the transition,
        // and emit SchematicCaptureFailedEvent so operators can be notified.
        // Gated on schematics.enabled (REQ-273) so capture is never attempted
        // when snapshots are disabled. Idempotent with capture-on-import
        // (WorldEditSchematicAdapter skips when a snapshot already exists).
        if (captureFailed(auction, stall, bid)) return

        val updatedStall = awardWinner(auction, stall, bid)
        fireStateChanged(stall.id.value, stall.state, updatedStall.state)
        notifyWinner(stall, bid)
        syncAwardedRegion(updatedStall, bid.bidder)
        paySeller(auction, stall, bid)
    }

    private fun awardWinner(auction: Auction, stall: Stall, bid: Bid): Stall {
        val awardAt = clock.instant()
        val updatedStall = stall.awardTo(
            OwnerRef.solo(bid.bidder),
            bid.amount,
            awardAt,
            awardAt.plus(RentTimingPolicy.collectionInterval(config)),
        )
        auctionRepository.save(auction.close())
        try {
            stallRepository.save(updatedStall)
        } catch (failure: Exception) {
            handleAwardFailure(auction, stall, bid, failure)
            throw failure
        }
        return updatedStall
    }

    private fun handleAwardFailure(auction: Auction, stall: Stall, bid: Bid, failure: Exception) {
        logger.severe(
            "settleWithWinner: stall save failed for auction ${auction.id} after close + charge; " +
                "refunding winner ${bid.bidder} (${bid.amount}) and leaving the auction closed. " +
                "cause=${failure.message}"
        )
        if (!refundOrLog(bid.bidder, bid.amount, "stall-save failure refund for auction ${auction.id}")) {
            logger.severe(
                "settleWithWinner: REFUND FAILED for winner ${bid.bidder} (${bid.amount}) on auction " +
                    "${auction.id} after stall-save failure; winner is charged with no stall and no " +
                    "refund; manual intervention required."
            )
        }
        revertAfterFailedAward(stall, bid)
    }

    private fun revertAfterFailedAward(stall: Stall, bid: Bid) {
        if (!systemAuctioned(stall)) return
        try {
            stallRepository.save(stall.copy(state = StallState.UNOWNED))
            fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
        } catch (revert: Exception) {
            logger.severe(
                "settleWithWinner: failed to revert stall ${stall.id.value} to UNOWNED after refunding " +
                    "${bid.bidder}; stall may be stuck AUCTIONING but the winner was refunded. " +
                    "cause=${revert.message}"
            )
        }
    }

    private fun notifyWinner(stall: Stall, bid: Bid) {
        runCatching { Bukkit.getPlayer(bid.bidder) }.getOrNull()?.sendMessage(
            lang.msg("auction.won", "stall" to stall.id.value, "amount" to bid.amount)
        )
    }

    private fun syncAwardedRegion(stall: Stall, winner: UUID) {
        try {
            regionMembers.setOwner(stall.world, stall.regionId, winner)
        } catch (failure: Exception) {
            logger.warning(
                "settleWithWinner: WG owner sync failed for stall ${stall.id.value}; " +
                    "DB owner is correct. cause=${failure.message}"
            )
        }
    }

    private fun paySeller(auction: Auction, stall: Stall, bid: Bid) {
        val feeAmount = (bid.amount * config.auction.feePct).toLong()
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
     * Close [auction] without awarding [stall] to anyone. A stall that the
     * system mass-auctioned (AUCTIONING + no owner) is reverted to UNOWNED so
     * it returns to the buyable pool; an owner-created auction leaves the
     * stall untouched.
     *
     * The auction is closed FIRST (matching the C3 settle ordering): if the
     * stall revert ran first and threw, the auction would stay OPEN-and-expired
     * and re-settle every scheduler tick — the exact wedge M-2 fixes. The
     * revert is best-effort; a stuck-AUCTIONING stall is operator-fixable,
     * an infinitely re-settling auction is not.
     */
    private fun closeWithoutAward(auction: Auction, stall: Stall) {
        auctionRepository.save(auction.close())
        // Revert any system-auctioned stall (all auctioning states + no owner)
        // back to UNOWNED so it returns to the buyable pool.
        if (systemAuctioned(stall)) {
            try {
                stallRepository.save(stall.copy(state = StallState.UNOWNED))
                fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
            } catch (e: Exception) {
                logger.severe(
                    "closeWithoutAward: auction ${auction.id} closed but stall ${stall.id.value} " +
                        "could not be reverted to UNOWNED — fix manually or re-run the mass auction. " +
                        "cause=${e.message}"
                )
            }
        }
    }

    private fun refundOrLog(player: UUID, amount: Long, context: String): Boolean {
        if (amount <= 0L) return true
        return try {
            if (economy.deposit(player, amount)) true else {
                logger.severe("REFUND FAILED: player=$player amount=$amount context=$context; manual intervention required.")
                false
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "REFUND FAILED: player=$player amount=$amount context=$context; manual intervention required.", e)
            false
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

    /**
     * Fire-and-forget StallStateChangedEvent. Bukkit may be unavailable
     * in unit-test contexts; the null check on `getServer()` keeps the
     * call safe for callers that don't bootstrap a MockBukkit server.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun fireStateChanged(stallId: String, previous: StallState, current: StallState) {
        if (previous == current) return
        try {
            org.bukkit.Bukkit.getServer()?.pluginManager?.callEvent(
                StallStateChangedEvent(stallId, previous, current)
            )
        } catch (e: Exception) {
            logger.warning("Failed to fire StallStateChangedEvent for $stallId: ${e.message}")
        }
    }

    private fun fireCaptureFailed(stallId: String, world: String, regionId: String, cause: Throwable) {
        try {
            org.bukkit.Bukkit.getServer()?.pluginManager?.callEvent(
                net.badgersmc.em.events.SchematicCaptureFailedEvent(stallId, world, regionId, cause)
            )
        } catch (e: Exception) {
            logger.warning("Failed to fire SchematicCaptureFailedEvent for $stallId: ${e.message}")
        }
    }
}
