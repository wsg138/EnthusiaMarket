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
import net.badgersmc.em.domain.ports.MarketMutationGate
import net.badgersmc.em.domain.shop.ShopRepository
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
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class AuctionLifecycleService(
    private val auctionRepository: AuctionRepository,
    private val stallRepository: StallRepository,
    private val economy: EconomyProvider,
    private val config: EnthusiaMarketConfig,
    private val limits: LimitResolutionService,
    private val sellOffers: SellOfferRepository,
    private val shops: ShopRepository,
    private val regionMembers: net.badgersmc.em.domain.ports.RegionMemberSync,
    private val ownership: StallOwnershipCounter,
    private val ipLimiter: IpLimiter,
    private val schematics: net.badgersmc.em.domain.ports.SchematicService =
        net.badgersmc.em.domain.ports.SchematicService.Disabled,
    private val lang: LangService,
    private val moderationPolicy: MarketModerationPolicy = MarketModerationPolicy.AllowAll,
    private val mutationGate: MarketMutationGate = MarketMutationGate.Open,
) {
    private val logger = Logger.getLogger(AuctionLifecycleService::class.java.name)

    /** Injectable clock for deterministic time-travel in tests. */
    internal var clock: Clock = Clock.systemUTC()

    private data class BidAttempt(
        val playerUuid: UUID,
        val amount: Long,
        val ip: String,
    )

    private data class SettlementContext(
        val bid: Bid,
        val stall: Stall,
    )

    private data class MassAuctionRequest(
        val now: Instant,
        val endAt: Instant,
        val antiSnipe: Duration,
        val antiSnipeExtend: Duration,
        val startingBid: Long,
    )

    private data class MassAuctionProgress(
        val created: MutableList<AuctionId> = mutableListOf(),
        var skipped: Int = 0,
        var errors: Int = 0,
    ) {
        fun record(result: Pair<AuctionId?, String>?) {
            val id = result?.first
            when {
                result == null -> skipped++
                id != null -> created.add(id)
                else -> errors++
            }
        }

        fun report() = MassAuctionResult.Report(created.size, skipped, errors, created)
    }

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
        creationFailure(stallId, playerUuid, startingBid)?.let { return it }
        val duration = resolveDuration(durationStr)
            ?: return AuctionResult.Failure("Duration resolution failed — this should not happen")
        val auction = buildAuction(stallId, startingBid, duration)
        auctionRepository.create(auction)
        return AuctionResult.Success(auction)
    }

    private fun creationFailure(
        stallId: StallId,
        playerUuid: UUID,
        startingBid: Long,
    ): AuctionResult.Failure? =
        ownershipCreationFailure(stallId, playerUuid)
            ?: conflictCreationFailure(stallId)
            ?: validateStartingBid(startingBid)

    private fun ownershipCreationFailure(stallId: StallId, playerUuid: UUID): AuctionResult.Failure? {
        val stall = stallRepository.findById(stallId)
            ?: return AuctionResult.Failure("Stall not found: ${stallId.value}")
        if (mutationGate.isStallLocked(stallId.value)) {
            return AuctionResult.Failure("This stall is temporarily unavailable")
        }
        return if (stall.owner != OwnerRef.solo(playerUuid)) {
            AuctionResult.Failure("You are not the owner of this stall")
        } else null
    }

    private fun conflictCreationFailure(stallId: StallId): AuctionResult.Failure? = when {
        auctionRepository.findOpenByStall(stallId) != null ->
            AuctionResult.Failure("An open auction already exists for this stall")
        sellOffers.findByStall(stallId) != null ->
            AuctionResult.Failure("An open sell offer already exists for this stall")
        else -> null
    }

    private fun buildAuction(stallId: StallId, startingBid: Long, duration: Duration): Auction {
        val now = clock.instant()
        return Auction(
            id = AuctionId(UUID.randomUUID().toString()),
            stallId = stallId,
            state = AuctionState.OPEN,
            startAt = now,
            endAt = now.plus(duration),
            startingBid = startingBid,
            highBid = null,
            antiSnipeWindow = config.auction.antiSnipeWindowDuration,
            antiSnipeExtension = config.auction.antiSnipeExtensionDuration,
        )
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
        return executeMassAuction(massAuctionRequest(duration, startingBid))
    }

    private fun massAuctionRequest(duration: Duration, startingBid: Long): MassAuctionRequest {
        val now = clock.instant()
        return MassAuctionRequest(
            now,
            now.plus(duration),
            config.auction.antiSnipeWindowDuration,
            config.auction.antiSnipeExtensionDuration,
            startingBid,
        )
    }

    private fun executeMassAuction(request: MassAuctionRequest): MassAuctionResult.Report {
        val progress = MassAuctionProgress()
        for (stall in stallRepository.byState(StallState.UNOWNED)) {
            progress.record(startAuctionForStall(stall, request))
        }
        return progress.report()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startAuctionForStall(stall: Stall, request: MassAuctionRequest): Pair<AuctionId?, String>? = try {
        startAuctionForUnlockedStall(stall, request)
    } catch (failure: Exception) {
        logger.warning("startAuctionForStall: stall ${stall.id} failed — ${failure.message}")
        Pair(null, "error")
    }

    private fun startAuctionForUnlockedStall(
        stall: Stall,
        request: MassAuctionRequest,
    ): Pair<AuctionId?, String>? {
        if (mutationGate.isStallLocked(stall.id.value)) return null
        if (auctionRepository.findOpenByStall(stall.id) != null) return null
        val auction = buildMassAuction(stall, request)
        auctionRepository.create(auction)
        persistAuctioningState(stall, auction)
        return Pair(auction.id, "created")
    }

    private fun buildMassAuction(stall: Stall, request: MassAuctionRequest) = Auction(
        id = AuctionId(UUID.randomUUID().toString()),
        stallId = stall.id,
        state = AuctionState.OPEN,
        startAt = request.now,
        endAt = request.endAt,
        startingBid = request.startingBid,
        highBid = null,
        antiSnipeWindow = request.antiSnipe,
        antiSnipeExtension = request.antiSnipeExtend,
    )

    private fun persistAuctioningState(stall: Stall, auction: Auction) {
        try {
            stallRepository.save(stall.copy(state = StallState.AUCTIONING))
            fireStateChanged(stall.id.value, stall.state, StallState.AUCTIONING)
        } catch (failure: Exception) {
            compensateFailedStallTransition(stall, auction)
            throw failure
        }
    }

    private fun compensateFailedStallTransition(stall: Stall, auction: Auction) {
        try {
            auctionRepository.save(auction.close())
        } catch (failure: Exception) {
            logger.warning(
                "startAuctionForStall: failed to compensate auction ${auction.id} " +
                    "after stall save failed for ${stall.id}: ${failure.message}"
            )
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
        val attempt = BidAttempt(playerUuid, amount, ip)
        return try {
            moderationPolicy.withAcquisitionPermit(playerUuid) {
                placeBidWithPermit(auctionId, attempt)
            }
        } catch (blocked: MarketAcquisitionBlockedException) {
            AuctionResult.Failure(blocked.message ?: "Market acquisitions are restricted")
        }
    }

    private fun placeBidWithPermit(auctionId: AuctionId, attempt: BidAttempt): AuctionResult {
        val auction = findAuction(auctionId) ?: return AuctionResult.NotFound
        bidAvailabilityFailure(auction)?.let { return it }
        val reservation = ipLimiter.acquireAuction(attempt.ip, auction.id.value)
        if (!reservation.allowed) {
            return AuctionResult.Failure("You already have an active bid on another auction.")
        }
        var completed = false
        return try {
            val result = placeReservedBid(auction, attempt)
            completed = result is AuctionResult.Success
            result
        } finally {
            if (!completed) ipLimiter.rollback(reservation.reservation)
        }
    }

    private fun bidAvailabilityFailure(auction: Auction): AuctionResult.Failure? = when {
        mutationGate.isStallLocked(auction.stallId.value) ->
            AuctionResult.Failure("This stall is temporarily unavailable")
        auction.state != AuctionState.OPEN -> AuctionResult.Failure("Auction is not open")
        else -> null
    }

    private fun placeReservedBid(auction: Auction, attempt: BidAttempt): AuctionResult {
        val updated = try {
            auction.placeBid(attempt.playerUuid, attempt.amount, clock.instant())
        } catch (e: IllegalArgumentException) {
            return AuctionResult.Failure(e.message ?: "Bid rejected")
        } catch (e: IllegalStateException) {
            return AuctionResult.Failure(e.message ?: "Bid rejected")
        }
        return finalizeBid(auction, updated, attempt.playerUuid, attempt.amount)
    }

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
        refundPreviousBidderIfOutbid(previousBid, playerUuid, updated, newBidderName)
        return AuctionResult.Success(updated)
    }

    private fun computeCharge(
        previousBid: Bid?,
        playerUuid: UUID,
        amount: Long,
    ): Long? {
        val charge = if (previousBid?.bidder == playerUuid) amount - previousBid.amount else amount
        return charge.takeIf { it > 0L }
    }

    private fun findAuction(auctionId: AuctionId) =
        auctionRepository.findById(auctionId)
            ?: auctionRepository.findOpenByStall(StallId(auctionId.value))

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

    private fun refundPreviousBidderIfOutbid(
        previousBid: Bid?,
        playerUuid: UUID,
        updated: Auction,
        newBidderName: String,
    ) {
        if (previousBid == null || previousBid.bidder == playerUuid) return
        refundOrLog(
            previousBid.bidder,
            previousBid.amount,
            "previous high-bidder refund after outbid on auction ${updated.id}",
        )
        val newAmount = updated.highBid?.amount ?: return
        runCatching { Bukkit.getPlayer(previousBid.bidder) }.getOrNull()?.sendMessage(
            lang.msg("auction.outbid", "stall" to updated.stallId.value, "amount" to newAmount, "bidder" to newBidderName)
        )
    }

    /**
     * Cancel an open auction. Only the stall owner may cancel.
     */
    fun cancelAuction(auctionId: AuctionId, playerUuid: UUID): AuctionResult {
        val auction = auctionRepository.findById(auctionId) ?: return AuctionResult.NotFound
        if (mutationGate.isStallLocked(auction.stallId.value)) {
            return AuctionResult.Failure("This stall is temporarily unavailable")
        }
        val stall = stallRepository.findById(auction.stallId)
            ?: return AuctionResult.Failure("Stall not found for auction")
        cancelAuthorizationFailure(stall, playerUuid)?.let { return it }
        return closeCancelledAuction(auction, stall)
    }

    private fun cancelAuthorizationFailure(stall: Stall, playerUuid: UUID): AuctionResult.Failure? =
        when (stall.owner.type) {
            OwnerType.SOLO -> if (stall.owner.id == playerUuid.toString()) null else
                AuctionResult.Failure("Only the stall owner can cancel this auction")
            OwnerType.GUILD -> AuctionResult.Failure("Guild-owned auctions cannot be cancelled this way")
            OwnerType.NONE -> null
        }

    private fun closeCancelledAuction(auction: Auction, stall: Stall): AuctionResult {
        val closed = auction.close()
        auctionRepository.save(closed)
        ipLimiter.releaseAuctionBindings(auction.id.value)
        auction.highBid?.let {
            refundOrLog(it.bidder, it.amount, "cancelAuction refund for auction ${auction.id}")
        }
        if (stall.state == StallState.AUCTIONING && stall.owner.type == OwnerType.NONE) {
            releaseAuctionedStall(stall, "cancelAuction")
        }
        return AuctionResult.Success(closed)
    }

    /** Extend an open auction's end time by the given duration. */
    fun extendAuction(auctionId: AuctionId, extensionStr: String): AuctionResult {
        val auction = auctionRepository.findById(auctionId)
            ?: auctionRepository.findOpenByStall(StallId(auctionId.value))
            ?: return AuctionResult.NotFound
        if (mutationGate.isStallLocked(auction.stallId.value)) {
            return AuctionResult.Failure("This stall is temporarily unavailable")
        }

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

    /** Clear stale high-bid data from all non-open auctions for a stall. */
    fun clearStaleBidData(stallId: StallId): Int {
        val auctions = auctionRepository.findByStall(stallId)
        var cleared = 0
        for (auction in auctions) {
            if (auction.state == AuctionState.OPEN) continue
            if (auction.highBid == null) continue
            auctionRepository.save(auction.copy(highBid = null))
            cleared++
        }
        return cleared
    }

    fun cancelAllAuctions(): Int {
        val open = auctionRepository.allOpen()
        var count = 0
        var errors = 0
        val auctioningStates = auctioningStates()
        for (auction in open) {
            if (cancelOneAuction(auction, auctioningStates)) count++ else errors++
        }
        if (errors > 0) logger.warning("cancelAllAuctions: $errors error(s) during batch cancel")
        return count
    }

    private fun cancelOneAuction(auction: Auction, auctioningStates: Set<StallState>): Boolean {
        if (mutationGate.isStallLocked(auction.stallId.value)) return false
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

    private fun canRevertStall(stall: Stall, auctioningStates: Set<StallState>): Boolean =
        stall.state in auctioningStates &&
            (stall.owner.type == OwnerType.NONE || stall.state == StallState.EMERGENCY_AUCTIONING)

    private fun revertSystemAuctionedStall(auction: Auction, auctioningStates: Set<StallState>) {
        val stall = stallRepository.findById(auction.stallId)
        if (stall != null && canRevertStall(stall, auctioningStates)) {
            releaseAuctionedStall(stall, "cancelAllAuctions")
        }
    }

    /** Settle all expired auctions. */
    @Suppress("NestedBlockDepth")
    fun settleExpired(): SettlementReport {
        val expired = auctionRepository.findExpired()
        var settled = 0
        var errors = 0

        for (auction in expired) {
            if (mutationGate.isStallLocked(auction.stallId.value)) continue
            try {
                if (auction.highBid != null) {
                    settleWithWinner(auction)
                } else {
                    settleWithoutBid(auction)
                }
                settled++
                ipLimiter.releaseAuctionBindings(auction.id.value)
            } catch (e: Exception) {
                errors++
            }
        }

        return SettlementReport(settled = settled, errors = errors)
    }

    private fun settleWithoutBid(auction: Auction) {
        val stall = stallRepository.findById(auction.stallId)
        if (stall != null && canRevertStall(stall, auctioningStates())) {
            releaseAuctionedStall(stall, "settleExpired no-bid")
            cleanupLingeringSellOffer(auction.stallId)
        }
        auctionRepository.save(auction.close())
    }

    private fun cleanupLingeringSellOffer(stallId: StallId) {
        if (sellOffers.findByStall(stallId) == null) return
        try {
            sellOffers.delete(stallId)
        } catch (failure: Exception) {
            logger.warning(
                "AuctionLifecycleService: failed to cleanup lingering sell offer for " +
                    "${stallId.value}. cause=${failure.message}"
            )
        }
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

    private fun settleWithWinnerWithPermit(auction: Auction) {
        val context = settlementContext(auction) ?: return
        if (rejectOverLimitWinner(auction, context)) return
        if (!captureSettlementSnapshot(auction, context)) return
        val updatedStall = persistSettlementAward(auction, context)
        notifyWinner(context)
        syncWinnerRegion(updatedStall, context.bid)
        payAuctionSeller(auction, context)
    }

    private fun settlementContext(auction: Auction): SettlementContext? {
        val bid = auction.highBid ?: return null
        val stall = stallRepository.findById(auction.stallId)
            ?: throw IllegalStateException("Stall not found for auction ${auction.id}")
        return SettlementContext(bid, stall)
    }

    private fun rejectOverLimitWinner(auction: Auction, context: SettlementContext): Boolean {
        val counts = ownership.counts(context.bid.bidder)
        val decision = limits.canClaim(
            context.bid.bidder,
            context.stall.kind,
            counts.total,
            counts.byKind[context.stall.kind] ?: 0,
        )
        if (decision !is LimitResolutionService.ClaimDecision.Rejected) return false
        logger.info("Auction ${auction.id} winner ${context.bid.bidder} over limit ($decision); refunding without award")
        closeWithoutAward(auction, context.stall)
        refundOrLog(context.bid.bidder, context.bid.amount, "limit rejection refund for auction ${auction.id}")
        return true
    }

    private fun captureSettlementSnapshot(auction: Auction, context: SettlementContext): Boolean {
        if (!config.schematics.enabled) return true
        val stall = context.stall
        val capture = schematics.capture(stall.id.value, stall.world, stall.regionId)
        if (capture !is net.badgersmc.em.domain.ports.SchematicService.Result.Failure) return true
        logger.warning(
            "settleWithWinner: schematic capture failed for stall ${stall.id.value}; " +
                "aborting award and refunding ${context.bid.bidder}. cause=${capture.cause.message}"
        )
        closeWithoutAward(auction, stall)
        refundOrLog(context.bid.bidder, context.bid.amount, "schematic failure refund for auction ${auction.id}")
        fireCaptureFailed(stall.id.value, stall.world, stall.regionId, capture.cause)
        return false
    }

    private fun persistSettlementAward(auction: Auction, context: SettlementContext): Stall {
        val awardAt = clock.instant()
        val updated = context.stall.awardTo(
            OwnerRef.solo(context.bid.bidder),
            context.bid.amount,
            awardAt,
            awardAt.plus(RentTimingPolicy.collectionInterval(config)),
        )
        auctionRepository.save(auction.close())
        try {
            stallRepository.save(updated)
        } catch (failure: Exception) {
            compensateFailedAward(auction, context, failure)
            throw failure
        }
        cleanupPreviousOwnershipShops(updated, "settleWithWinner")
        fireStateChanged(context.stall.id.value, context.stall.state, updated.state)
        return updated
    }

    private fun compensateFailedAward(auction: Auction, context: SettlementContext, cause: Exception) {
        logger.severe(
            "settleWithWinner: stall save failed for auction ${auction.id}; refunding " +
                "${context.bid.bidder} (${context.bid.amount}). cause=${cause.message}"
        )
        if (!refundOrLog(context.bid.bidder, context.bid.amount, "stall-save failure refund for auction ${auction.id}")) {
            logger.severe("settleWithWinner: refund also failed for ${context.bid.bidder} on ${auction.id}; manual intervention required")
        }
        revertFailedAward(context.stall, context.bid)
    }

    private fun revertFailedAward(stall: Stall, bid: Bid) {
        if (!canRevertStall(stall, auctioningStates())) return
        try {
            releaseAuctionedStall(stall, "settleWithWinner rollback")
        } catch (failure: Exception) {
            logger.severe(
                "settleWithWinner: failed to revert stall ${stall.id.value} after refunding ${bid.bidder}; " +
                    "stall may be stuck auctioning. cause=${failure.message}"
            )
        }
    }

    private fun notifyWinner(context: SettlementContext) {
        runCatching { Bukkit.getPlayer(context.bid.bidder) }.getOrNull()?.sendMessage(
            lang.msg("auction.won", "stall" to context.stall.id.value, "amount" to context.bid.amount)
        )
    }

    private fun syncWinnerRegion(updatedStall: Stall, bid: Bid) {
        try {
            regionMembers.setOwner(updatedStall.world, updatedStall.regionId, bid.bidder)
        } catch (failure: Exception) {
            logger.warning(
                "settleWithWinner: WG owner sync failed for stall ${updatedStall.id.value}; " +
                    "DB owner is correct. cause=${failure.message}"
            )
        }
    }

    private fun payAuctionSeller(auction: Auction, context: SettlementContext) {
        val feeAmount = (context.bid.amount * config.auction.feePct).toLong()
        val proceeds = context.bid.amount - feeAmount
        val sellerUuid = extractOwnerUuid(context.stall)
        if (sellerUuid == null || !economy.deposit(sellerUuid, proceeds)) {
            logger.warning(
                "Auction ${auction.id}: seller payment failed. " +
                    "Winner charged ${context.bid.amount}, seller proceeds $proceeds pending."
            )
        }
    }

    private fun cleanupPreviousOwnershipShops(stall: Stall, context: String) {
        try {
            for (shop in shops.findByStall(stall.id.value)) {
                if (shop.adminShop) continue
                try {
                    shops.delete(shop.id)
                } catch (failure: Exception) {
                    logger.warning(
                        "$context: failed to remove previous shop ${shop.id} from " +
                            "stall ${stall.id.value}. cause=${failure.message}"
                    )
                }
            }
        } catch (failure: Exception) {
            logger.warning(
                "$context: failed to enumerate previous shops for stall ${stall.id.value}. " +
                    "cause=${failure.message}"
            )
        }
    }

    private fun releaseAuctionedStall(stall: Stall, context: String) {
        val previousOwnerId = stall.owner.id.takeIf {
            stall.owner.type != OwnerType.NONE && it.isNotBlank()
        }
        stallRepository.save(stall.releaseOwnership())
        previousOwnerId?.let(ipLimiter::releaseStallByOwnerId)
        cleanupPreviousOwnershipShops(stall, context)
        try {
            regionMembers.clearOwnersAndMembers(stall.world, stall.regionId)
        } catch (failure: Exception) {
            logger.warning(
                "$context: failed to clear region access for stall ${stall.id.value}. " +
                    "cause=${failure.message}"
            )
        }
        fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
    }

    private fun closeWithoutAward(auction: Auction, stall: Stall) {
        auctionRepository.save(auction.close())
        if (!canRevertStall(stall, auctioningStates())) return
        try {
            releaseAuctionedStall(stall, "closeWithoutAward")
        } catch (failure: Exception) {
            logger.severe(
                "closeWithoutAward: auction ${auction.id} closed but stall ${stall.id.value} " +
                    "could not be reverted to UNOWNED. cause=${failure.message}"
            )
        }
    }

    private fun auctioningStates(): Set<StallState> = setOf(
        StallState.AUCTIONING,
        StallState.RE_AUCTIONING,
        StallState.EMERGENCY_AUCTIONING,
    )

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

    private fun extractOwnerUuid(stall: Stall): UUID? {
        return if (stall.owner.type == OwnerType.SOLO) {
            try {
                UUID.fromString(stall.owner.id)
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fireStateChanged(stallId: String, previous: StallState, current: StallState) {
        if (previous == current) return
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                StallStateChangedEvent(stallId, previous, current)
            )
        } catch (e: Exception) {
            logger.warning("Failed to fire StallStateChangedEvent for $stallId: ${e.message}")
        }
    }

    private fun fireCaptureFailed(stallId: String, world: String, regionId: String, cause: Throwable) {
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                net.badgersmc.em.events.SchematicCaptureFailedEvent(stallId, world, regionId, cause)
            )
        } catch (e: Exception) {
            logger.warning("Failed to fire SchematicCaptureFailedEvent for $stallId: ${e.message}")
        }
    }
}
