package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.auction.AuctionState
import net.badgersmc.em.domain.auction.Bid
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
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
) {
    private val logger = Logger.getLogger(AuctionLifecycleService::class.java.name)

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
        val auction = findAuction(auctionId) ?: return AuctionResult.NotFound

        if (auction.state != AuctionState.OPEN) {
            return AuctionResult.Failure("Auction is not open")
        }

        val reservation = ipLimiter.acquireAuction(ip, auction.id.value)
        if (!reservation.allowed) {
            return AuctionResult.Failure("You already have an active bid on another auction.")
        }

        val updated = try {
            auction.placeBid(playerUuid, amount, clock.instant())
        } catch (e: IllegalArgumentException) {
            ipLimiter.rollback(reservation.reservation)
            return AuctionResult.Failure(e.message ?: "Bid rejected")
        } catch (e: IllegalStateException) {
            ipLimiter.rollback(reservation.reservation)
            return AuctionResult.Failure(e.message ?: "Bid rejected")
        }

        val result = finalizeBid(auction, updated, playerUuid, amount)
        if (result !is AuctionResult.Success) ipLimiter.rollback(reservation.reservation)
        return result
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
    private fun findAuction(auctionId: AuctionId) =
        auctionRepository.findById(auctionId)
            ?: auctionRepository.findOpenByStall(StallId(auctionId.value))

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
    @Suppress("LongMethod")
    fun cancelAllAuctions(): Int {
        val open = auctionRepository.allOpen()
        var count = 0
        var errors = 0
        val auctioningStates = setOf(
            StallState.AUCTIONING,
            StallState.RE_AUCTIONING,
            StallState.EMERGENCY_AUCTIONING,
        )
        for (auction in open) {
            if (cancelOneAuction(auction, auctioningStates)) count++ else errors++
        }
        if (errors > 0) logger.warning("cancelAllAuctions: $errors error(s) during batch cancel")
        return count
    }

    private fun cancelOneAuction(auction: Auction, auctioningStates: Set<StallState>): Boolean {
        return try {
            val cancelled = auction.copy(state = AuctionState.CANCELLED)
            auctionRepository.save(cancelled)
            auction.highBid?.let {
                refundOrLog(it.bidder, it.amount, "cancelAllAuctions refund for auction ${auction.id}")
            }
            ipLimiter.releaseAuctionBindings(auction.id.value)
            revertSystemAuctionedStall(auction, auctioningStates)
            true
        } catch (e: Exception) {
            logger.warning("cancelAllAuctions: failed to cancel auction ${auction.id}: ${e.message}")
            false
        }
    }

    private fun revertSystemAuctionedStall(auction: Auction, auctioningStates: Set<StallState>) {
        val stall = stallRepository.findById(auction.stallId)
        if (stall != null && stall.state in auctioningStates && stall.owner.type == OwnerType.NONE) {
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
    @Suppress("NestedBlockDepth")
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
                        && stall.state in setOf(StallState.AUCTIONING, StallState.RE_AUCTIONING, StallState.EMERGENCY_AUCTIONING)
                    ) {
                        stallRepository.save(stall.copy(state = StallState.UNOWNED))
                        // M3 — drop any lingering sell offer on the
                        // now-UNOWNED stall so a follow-up click doesn't
                        // trip the offer-mutex check (matches
                        // StallBuyoutService cleanup pattern: best-effort,
                        // logged, never re-thrown).
                        if (sellOffers.findByStall(auction.stallId) != null) {
                            try {
                                sellOffers.delete(auction.stallId)
                            } catch (cleanupErr: Exception) {
                                logger.warning(
                                    "AuctionLifecycleService: failed to cleanup lingering sell offer for " +
                                        "${auction.stallId.value}. cause=${cleanupErr.message}"
                                )
                            }
                        }
                        fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
                    }
                    auctionRepository.save(auction.close())
                }
                settled++
                ipLimiter.releaseAuctionBindings(auction.id.value)
            } catch (e: Exception) {
                errors++
            }
        }

        return SettlementReport(settled = settled, errors = errors)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount")
    private fun settleWithWinner(auction: Auction) {
        val bid = auction.highBid ?: return
        val stall = stallRepository.findById(auction.stallId)
            ?: throw IllegalStateException("Stall not found for auction ${auction.id}")

        // REQ-212 — limit gate. The winner already paid at bid time, so close
        // the auction first to avoid retry/double-refund loops, then refund.
        val counts = ownership.counts(bid.bidder)
        val decision = limits.canClaim(
            player = bid.bidder,
            kind = stall.kind,
            currentTotal = counts.total,
            currentForKind = counts.byKind[stall.kind] ?: 0,
        )
        if (decision is LimitResolutionService.ClaimDecision.Rejected) {
            logger.info(
                "Auction ${auction.id} winner ${bid.bidder} over limit " +
                    "($decision); refunding and reverting without award."
            )
            closeWithoutAward(auction, stall)
            refundOrLog(bid.bidder, bid.amount, "limit rejection refund for auction ${auction.id}")
            return
        }

        // Winner already paid at bid time. Settlement must not charge again.

        // 0.5 REQ-270/273/274 — snapshot the stall geometry BEFORE finalising
        // ownership. On failure: log, refund the winner, abort the transition,
        // and emit SchematicCaptureFailedEvent so operators can be notified.
        // Gated on schematics.enabled (REQ-273) so capture is never attempted
        // when snapshots are disabled. Idempotent with capture-on-import
        // (WorldEditSchematicAdapter skips when a snapshot already exists).
        if (config.schematics.enabled) {
            val capture = schematics.capture(stall.id.value, stall.world, stall.regionId)
            if (capture is net.badgersmc.em.domain.ports.SchematicService.Result.Failure) {
                logger.warning(
                    "settleWithWinner: schematic capture failed for stall ${stall.id.value}; " +
                        "aborting award and refunding ${bid.bidder}. cause=${capture.cause.message}"
                )
                closeWithoutAward(auction, stall)
                refundOrLog(bid.bidder, bid.amount, "schematic failure refund for auction ${auction.id}")
                fireCaptureFailed(stall.id.value, stall.world, stall.regionId, capture.cause)
                return
            }
        }

        // 1. Persist state changes (stall awarded + auction closed).
        //
        // C3 fix — close the auction FIRST, then award the stall, and on a
        // stall-save failure REFUND the winner and leave the auction CLOSED.
        // The winner is already charged at step 0, which runs on every settle;
        // the old ordering (save stall, then close auction) left the auction
        // OPEN on a close-failure, so the scheduler re-settled and re-charged.
        // Closing first + refund-on-failure makes settle charge-exactly-once:
        //   • Success: auction closed, stall awarded — single charge.
        //   • Stall-save failure: winner refunded, auction stays CLOSED (no
        //     re-settle → no re-charge), stall best-effort reverted to UNOWNED
        //     so it can be re-auctioned later. The winner is made whole either
        //     way; no money is created or destroyed.
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
        } catch (e: Exception) {
            // Stall award failed after the auction was closed + the winner charged.
            // Refund the winner (no double-charge), keep the auction CLOSED so the
            // scheduler does not re-settle, and best-effort revert the stall so it
            // returns to the auctionable pool.
            logger.severe(
                "settleWithWinner: stall save failed for auction ${auction.id} after close + charge; " +
                    "refunding winner ${bid.bidder} (${bid.amount}) and leaving the auction closed. " +
                    "cause=${e.message}"
            )
            if (!refundOrLog(bid.bidder, bid.amount, "stall-save failure refund for auction ${auction.id}")) {
                logger.severe(
                    "settleWithWinner: REFUND FAILED for winner ${bid.bidder} (${bid.amount}) on auction " +
                        "${auction.id} after stall-save failure — winner is charged with no stall and no " +
                        "refund; manual intervention required."
                )
            }
            try {
                if (stall.state in setOf(StallState.AUCTIONING, StallState.RE_AUCTIONING, StallState.EMERGENCY_AUCTIONING) &&
                    stall.owner.type == OwnerType.NONE) {
                    stallRepository.save(stall.copy(state = StallState.UNOWNED))
                    fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
                }
            } catch (revert: Exception) {
                logger.severe(
                    "settleWithWinner: failed to revert stall ${stall.id.value} to UNOWNED after refunding " +
                        "${bid.bidder}; stall may be stuck AUCTIONING but the winner was refunded. " +
                        "cause=${revert.message}"
                )
            }
            throw e
        }
        fireStateChanged(stall.id.value, stall.state, updatedStall.state)

        // Notify the winner if online
        runCatching { Bukkit.getPlayer(bid.bidder) }.getOrNull()?.sendMessage(
            lang.msg("auction.won", "stall" to stall.id.value, "amount" to bid.amount)
        )

        // 2. Sync region AFTER persist (best-effort).
        // If this fails, the DB is correct; /em rg resync can fix WG.
        try {
            regionMembers.setOwner(updatedStall.world, updatedStall.regionId, bid.bidder)
        } catch (e: Exception) {
            logger.warning(
                "settleWithWinner: WG owner sync failed for stall ${updatedStall.id.value}; " +
                    "DB owner is correct. cause=${e.message}"
            )
        }

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
        if (stall.state in setOf(StallState.AUCTIONING, StallState.RE_AUCTIONING, StallState.EMERGENCY_AUCTIONING) &&
            stall.owner.type == net.badgersmc.em.domain.stall.OwnerType.NONE
        ) {
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

    @Suppress("LongMethod")
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
