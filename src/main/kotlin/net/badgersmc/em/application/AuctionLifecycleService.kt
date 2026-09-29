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

/** Result of an auction lifecycle operation. */
sealed class AuctionResult {
    /** Operation completed successfully. */
    data class Success(val auction: Auction) : AuctionResult()
    /** Operation failed with a descriptive reason. */
    data class Failure(val reason: String) : AuctionResult()
    /** Referenced auction was not found. */
    data object NotFound : AuctionResult()
}

/** Report from settling expired auctions. */
data class SettlementReport(
    val settled: Int,
    val errors: Int,
)

/** Outcome of [AuctionLifecycleService.startMassAuction]. */
sealed class MassAuctionResult {
    /** At least one stall was processed (created may still be 0 if all were skipped). */
    data class Report(
        val created: Int,
        val skipped: Int,
        val errors: Int,
        val auctionIds: List<AuctionId>,
    ) : MassAuctionResult()

    /** Input validation rejected the entire operation before any stall was touched. */
    data class Invalid(val reason: String) : MassAuctionResult()
}

/** Application-layer service managing the full auction lifecycle (REQ-007). */
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
    private val mutationGate: MarketMutationGate = MarketMutationGate.Open,
) {
    private val logger = Logger.getLogger(AuctionLifecycleService::class.java.name)
    private val auctioningStates = setOf(
        StallState.AUCTIONING,
        StallState.RE_AUCTIONING,
        StallState.EMERGENCY_AUCTIONING,
    )

    /** Injectable clock for deterministic time-travel in tests. */
    internal var clock: Clock = Clock.systemUTC()

    /** Create a new auction for a stall. */
    fun createAuction(
        stallId: StallId,
        playerUuid: UUID,
        startingBid: Long,
        durationStr: String?,
    ): AuctionResult {
        val stall = stallRepository.findById(stallId)
            ?: return AuctionResult.Failure("Stall not found: ${stallId.value}")
        if (mutationGate.isStallLocked(stallId.value)) {
            return AuctionResult.Failure("This stall is temporarily unavailable")
        }
        if (stall.owner != OwnerRef.solo(playerUuid)) {
            return AuctionResult.Failure("You are not the owner of this stall")
        }
        if (auctionRepository.findOpenByStall(stallId) != null) {
            return AuctionResult.Failure("An open auction already exists for this stall")
        }
        if (sellOffers.findByStall(stallId) != null) {
            return AuctionResult.Failure("An open sell offer already exists for this stall")
        }
        validateStartingBid(startingBid)?.let { return it }
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
            antiSnipeExtension = config.auction.antiSnipeExtensionDuration,
        )
        auctionRepository.create(auction)
        return AuctionResult.Success(auction)
    }

    /** Launch a system-initiated auction for every UNOWNED stall at once (REQ-028). */
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
        return MassAuctionResult.Report(created.size, skipped, errors, created)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startAuctionForStall(
        stall: Stall,
        now: Instant,
        endAt: Instant,
        antiSnipe: Duration,
        antiSnipeExtend: Duration,
        startingBid: Long,
    ): Pair<AuctionId?, String>? {
        try {
            if (mutationGate.isStallLocked(stall.id.value)) return null
            if (auctionRepository.findOpenByStall(stall.id) != null) return null
            val auction = Auction(
                id = AuctionId(UUID.randomUUID().toString()),
                stallId = stall.id,
                state = AuctionState.OPEN,
                startAt = now,
                endAt = endAt,
                startingBid = startingBid,
                highBid = null,
                antiSnipeWindow = antiSnipe,
                antiSnipeExtension = antiSnipeExtend,
            )
            auctionRepository.create(auction)
            try {
                stallRepository.save(stall.copy(state = StallState.AUCTIONING))
                fireStateChanged(stall.id.value, stall.state, StallState.AUCTIONING)
            } catch (stallErr: Exception) {
                compensateFailedAuctionStart(auction, stall)
                throw stallErr
            }
            return Pair(auction.id, "created")
        } catch (e: Exception) {
            logger.warning("startAuctionForStall: stall ${stall.id} failed — ${e.message}")
            return Pair(null, "error")
        }
    }

    private fun compensateFailedAuctionStart(auction: Auction, stall: Stall) {
        try {
            auctionRepository.save(auction.close())
        } catch (compErr: Exception) {
            logger.warning(
                "startAuctionForStall: failed to compensate auction ${auction.id} " +
                    "after stall save failed for ${stall.id}: ${compErr.message}",
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
            try { Duration.parse(durationStr) } catch (_: Exception) { return null }
        } else {
            Duration.parse(config.auction.defaultDuration)
        }
        return duration.takeIf { it >= minDuration && it <= maxDuration }
    }

    /** Place a bid on an open auction. */
    fun placeBid(auctionId: AuctionId, playerUuid: UUID, amount: Long, ip: String): AuctionResult = try {
        moderationPolicy.withAcquisitionPermit(playerUuid) {
            placeBidWithPermit(auctionId, playerUuid, amount, ip)
        }
    } catch (blocked: MarketAcquisitionBlockedException) {
        AuctionResult.Failure(blocked.message ?: "Market acquisitions are restricted")
    }

    private fun placeBidWithPermit(auctionId: AuctionId, playerUuid: UUID, amount: Long, ip: String): AuctionResult {
        val auction = findAuction(auctionId) ?: return AuctionResult.NotFound
        validateBidTarget(auction)?.let { return it }
        val reservation = ipLimiter.acquireAuction(ip, auction.id.value)
        if (!reservation.allowed) return AuctionResult.Failure("You already have an active bid on another auction.")
        var completed = false
        try {
            val result = createAndFinalizeBid(auction, playerUuid, amount)
            completed = result is AuctionResult.Success
            return result
        } finally {
            if (!completed) ipLimiter.rollback(reservation.reservation)
        }
    }

    private fun validateBidTarget(auction: Auction): AuctionResult.Failure? = when {
        mutationGate.isStallLocked(auction.stallId.value) ->
            AuctionResult.Failure("This stall is temporarily unavailable")
        auction.state != AuctionState.OPEN -> AuctionResult.Failure("Auction is not open")
        else -> null
    }

    private fun createAndFinalizeBid(auction: Auction, playerUuid: UUID, amount: Long): AuctionResult {
        val updated = try {
            auction.placeBid(playerUuid, amount, clock.instant())
        } catch (e: IllegalArgumentException) {
            return AuctionResult.Failure(e.message ?: "Bid rejected")
        } catch (e: IllegalStateException) {
            return AuctionResult.Failure(e.message ?: "Bid rejected")
        }
        return finalizeBid(auction, updated, playerUuid, amount)
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
        val bidderName = runCatching { Bukkit.getPlayer(playerUuid) }.getOrNull()?.name ?: "Unknown"
        refundPreviousBidderIfOutbid(previousBid, playerUuid, original, amount, bidderName)
        return AuctionResult.Success(updated)
    }

    private fun computeCharge(previousBid: Bid?, playerUuid: UUID, amount: Long): Long? {
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
        return try {
            auctionRepository.save(updated)
            null
        } catch (e: Exception) {
            refundOrLog(playerUuid, charge, "placeBid rollback after auction save failed for $auctionId")
            AuctionResult.Failure(e.message ?: "Bid rejected")
        }
    }

    private fun refundPreviousBidderIfOutbid(
        previousBid: Bid?,
        playerUuid: UUID,
        auction: Auction,
        newAmount: Long,
        newBidderName: String,
    ) {
        if (previousBid == null || previousBid.bidder == playerUuid) return
        refundOrLog(
            previousBid.bidder,
            previousBid.amount,
            "previous high-bidder refund after outbid on auction ${auction.id}",
        )
        runCatching { Bukkit.getPlayer(previousBid.bidder) }.getOrNull()?.sendMessage(
            lang.msg(
                "auction.outbid",
                "stall" to auction.stallId.value,
                "amount" to newAmount,
                "bidder" to newBidderName,
            ),
        )
    }

    /** Cancel an open auction. Only the stall owner may cancel. */
    fun cancelAuction(auctionId: AuctionId, playerUuid: UUID): AuctionResult {
        val auction = auctionRepository.findById(auctionId) ?: return AuctionResult.NotFound
        if (mutationGate.isStallLocked(auction.stallId.value)) {
            return AuctionResult.Failure("This stall is temporarily unavailable")
        }
        val stall = stallRepository.findById(auction.stallId)
            ?: return AuctionResult.Failure("Stall not found for auction")
        when (stall.owner.type) {
            OwnerType.SOLO -> if (stall.owner.id != playerUuid.toString()) {
                return AuctionResult.Failure("Only the stall owner can cancel this auction")
            }
            OwnerType.GUILD -> return AuctionResult.Failure("Guild-owned auctions cannot be cancelled this way")
            OwnerType.NONE -> Unit
        }
        val closed = auction.close()
        auctionRepository.save(closed)
        ipLimiter.releaseAuctionBindings(auction.id.value)
        auction.highBid?.let { refundOrLog(it.bidder, it.amount, "cancelAuction refund for auction ${auction.id}") }
        if (stall.state == StallState.AUCTIONING && stall.owner.type == OwnerType.NONE) {
            stallRepository.save(stall.copy(state = StallState.UNOWNED))
            fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
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
        } catch (_: Exception) {
            return AuctionResult.Failure("Invalid duration format: '$extensionStr'. Use ISO-8601 (e.g. PT6H, P1D)")
        }
        if (extension.isNegative || extension.isZero) {
            return AuctionResult.Failure("Extension must be a positive duration")
        }
        val newEndAt = auction.endAt.plus(extension)
        val maxEnd = clock.instant().plus(Duration.parse(config.auction.maxDuration))
        if (newEndAt.isAfter(maxEnd)) {
            return AuctionResult.Failure(
                "Extension would exceed maximum auction duration (${config.auction.maxDuration} from now)",
            )
        }
        val extended = auction.copy(endAt = newEndAt)
        auctionRepository.save(extended)
        return AuctionResult.Success(extended)
    }

    /** Clear stale high-bid data from all CANCELLED and CLOSED auctions for a stall. */
    fun clearStaleBidData(stallId: StallId): Int {
        var cleared = 0
        for (auction in auctionRepository.findByStall(stallId)) {
            if (auction.state == AuctionState.OPEN || auction.highBid == null) continue
            auctionRepository.save(auction.copy(highBid = null))
            cleared++
        }
        return cleared
    }

    /** Emergency mass-cancel all open auctions and refund any held high bids. */
    fun cancelAllAuctions(): Int {
        var count = 0
        var errors = 0
        for (auction in auctionRepository.allOpen()) {
            if (cancelOneAuction(auction)) count++ else errors++
        }
        if (errors > 0) logger.warning("cancelAllAuctions: $errors error(s) during batch cancel")
        return count
    }

    private fun cancelOneAuction(auction: Auction): Boolean {
        if (mutationGate.isStallLocked(auction.stallId.value)) return false
        return try {
            auctionRepository.save(auction.copy(state = AuctionState.CANCELLED))
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

    private fun canRevertStall(stall: Stall): Boolean =
        stall.state in auctioningStates &&
            (stall.owner.type == OwnerType.NONE || stall.state == StallState.EMERGENCY_AUCTIONING)

    private fun revertedStall(stall: Stall): Stall = if (stall.state == StallState.EMERGENCY_AUCTIONING) {
        stall.copy(state = StallState.UNOWNED, owner = OwnerRef.unowned())
    } else {
        stall.copy(state = StallState.UNOWNED)
    }

    private fun revertSystemAuctionedStall(auction: Auction) {
        val stall = stallRepository.findById(auction.stallId) ?: return
        if (!canRevertStall(stall)) return
        stallRepository.save(revertedStall(stall))
        fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
    }

    /** Settle all expired auctions. */
    fun settleExpired(): SettlementReport {
        var settled = 0
        var errors = 0
        for (auction in auctionRepository.findExpired()) {
            if (mutationGate.isStallLocked(auction.stallId.value)) continue
            try {
                settleExpiredAuction(auction)
                settled++
                ipLimiter.releaseAuctionBindings(auction.id.value)
            } catch (_: Exception) {
                errors++
            }
        }
        return SettlementReport(settled, errors)
    }

    private fun settleExpiredAuction(auction: Auction) {
        if (auction.highBid != null) {
            settleWithWinner(auction)
            return
        }
        val stall = stallRepository.findById(auction.stallId)
        if (stall != null && canRevertStall(stall)) {
            stallRepository.save(revertedStall(stall))
            cleanupLingeringSellOffer(auction.stallId)
            fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
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
                    "${stallId.value}. cause=${failure.message}",
            )
        }
    }

    private fun settleWithWinner(auction: Auction) {
        val bid = auction.highBid ?: return
        try {
            moderationPolicy.withAcquisitionPermit(bid.bidder) { settleWithWinnerWithPermit(auction, bid) }
        } catch (_: MarketAcquisitionBlockedException) {
            val stall = requireStall(auction)
            logger.info("Auction ${auction.id} winner is restricted; refunding without an ownership award")
            closeWithoutAward(auction, stall)
            refundOrLog(bid.bidder, bid.amount, "market restriction refund for auction ${auction.id}")
        }
    }

    private fun settleWithWinnerWithPermit(auction: Auction, bid: Bid) {
        val stall = requireStall(auction)
        if (rejectWinnerOverLimit(auction, stall, bid)) return
        if (!captureSettlementSnapshot(auction, stall, bid)) return
        val updatedStall = persistWinnerAward(auction, stall, bid)
        fireStateChanged(stall.id.value, stall.state, updatedStall.state)
        notifyWinner(stall, bid)
        syncWinnerRegion(updatedStall, bid)
        payAuctionSeller(auction, stall, bid)
    }

    private fun requireStall(auction: Auction): Stall =
        stallRepository.findById(auction.stallId)
            ?: throw IllegalStateException("Stall not found for auction ${auction.id}")

    private fun rejectWinnerOverLimit(auction: Auction, stall: Stall, bid: Bid): Boolean {
        val counts = ownership.counts(bid.bidder)
        val decision = limits.canClaim(
            player = bid.bidder,
            kind = stall.kind,
            currentTotal = counts.total,
            currentForKind = counts.byKind[stall.kind] ?: 0,
        )
        if (decision !is LimitResolutionService.ClaimDecision.Rejected) return false
        logger.info(
            "Auction ${auction.id} winner ${bid.bidder} over limit ($decision); " +
                "refunding and reverting without award.",
        )
        closeWithoutAward(auction, stall)
        refundOrLog(bid.bidder, bid.amount, "limit rejection refund for auction ${auction.id}")
        return true
    }

    private fun captureSettlementSnapshot(auction: Auction, stall: Stall, bid: Bid): Boolean {
        if (!config.schematics.enabled) return true
        val capture = schematics.capture(stall.id.value, stall.world, stall.regionId)
        if (capture !is net.badgersmc.em.domain.ports.SchematicService.Result.Failure) return true
        logger.warning(
            "settleWithWinner: schematic capture failed for stall ${stall.id.value}; " +
                "aborting award and refunding ${bid.bidder}. cause=${capture.cause.message}",
        )
        closeWithoutAward(auction, stall)
        refundOrLog(bid.bidder, bid.amount, "schematic failure refund for auction ${auction.id}")
        fireCaptureFailed(stall.id.value, stall.world, stall.regionId, capture.cause)
        return false
    }

    private fun persistWinnerAward(auction: Auction, stall: Stall, bid: Bid): Stall {
        val awardAt = clock.instant()
        val updated = stall.awardTo(
            OwnerRef.solo(bid.bidder),
            bid.amount,
            awardAt,
            awardAt.plus(RentTimingPolicy.collectionInterval(config)),
        )
        auctionRepository.save(auction.close())
        try {
            stallRepository.save(updated)
            return updated
        } catch (failure: Exception) {
            handleAwardSaveFailure(auction, stall, bid, failure)
            throw failure
        }
    }

    private fun handleAwardSaveFailure(auction: Auction, stall: Stall, bid: Bid, failure: Exception) {
        logger.severe(
            "settleWithWinner: stall save failed for auction ${auction.id} after close + charge; " +
                "refunding winner ${bid.bidder} (${bid.amount}) and leaving the auction closed. " +
                "cause=${failure.message}",
        )
        if (!refundOrLog(bid.bidder, bid.amount, "stall-save failure refund for auction ${auction.id}")) {
            logger.severe(
                "settleWithWinner: REFUND FAILED for winner ${bid.bidder} (${bid.amount}) on auction " +
                    "${auction.id}; manual intervention required.",
            )
        }
        revertAfterFailedAward(stall, bid)
    }

    private fun revertAfterFailedAward(stall: Stall, bid: Bid) {
        if (!canRevertStall(stall)) return
        try {
            stallRepository.save(revertedStall(stall))
            fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
        } catch (revert: Exception) {
            logger.severe(
                "settleWithWinner: failed to revert stall ${stall.id.value} to UNOWNED after refunding " +
                    "${bid.bidder}; stall may be stuck AUCTIONING but the winner was refunded. " +
                    "cause=${revert.message}",
            )
        }
    }

    private fun notifyWinner(stall: Stall, bid: Bid) {
        runCatching { Bukkit.getPlayer(bid.bidder) }.getOrNull()?.sendMessage(
            lang.msg("auction.won", "stall" to stall.id.value, "amount" to bid.amount),
        )
    }

    private fun syncWinnerRegion(stall: Stall, bid: Bid) {
        try {
            regionMembers.setOwner(stall.world, stall.regionId, bid.bidder)
        } catch (failure: Exception) {
            logger.warning(
                "settleWithWinner: WG owner sync failed for stall ${stall.id.value}; " +
                    "DB owner is correct. cause=${failure.message}",
            )
        }
    }

    private fun payAuctionSeller(auction: Auction, stall: Stall, bid: Bid) {
        val feeAmount = (bid.amount * config.auction.feePct).toLong()
        val sellerProceeds = bid.amount - feeAmount
        val sellerUuid = extractOwnerUuid(stall)
        if (sellerUuid != null && economy.deposit(sellerUuid, sellerProceeds)) return
        logger.warning(
            "Auction ${auction.id}: seller payment failed. " +
                "Winner charged ${bid.amount}, seller proceeds $sellerProceeds pending.",
        )
    }

    private fun closeWithoutAward(auction: Auction, stall: Stall) {
        auctionRepository.save(auction.close())
        if (!canRevertStall(stall)) return
        try {
            stallRepository.save(revertedStall(stall))
            fireStateChanged(stall.id.value, stall.state, StallState.UNOWNED)
        } catch (failure: Exception) {
            logger.severe(
                "closeWithoutAward: auction ${auction.id} closed but stall ${stall.id.value} " +
                    "could not be reverted to UNOWNED — fix manually or re-run the mass auction. " +
                    "cause=${failure.message}",
            )
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
            logger.log(
                Level.SEVERE,
                "REFUND FAILED: player=$player amount=$amount context=$context; manual intervention required.",
                e,
            )
            false
        }
    }

    private fun extractOwnerUuid(stall: Stall): UUID? {
        if (stall.owner.type != OwnerType.SOLO) return null
        return try {
            UUID.fromString(stall.owner.id)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fireStateChanged(stallId: String, previous: StallState, current: StallState) {
        if (previous == current) return
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(StallStateChangedEvent(stallId, previous, current))
        } catch (e: Exception) {
            logger.warning("Failed to fire StallStateChangedEvent for $stallId: ${e.message}")
        }
    }

    private fun fireCaptureFailed(stallId: String, world: String, regionId: String, cause: Throwable) {
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                net.badgersmc.em.events.SchematicCaptureFailedEvent(stallId, world, regionId, cause),
            )
        } catch (e: Exception) {
            logger.warning("Failed to fire SchematicCaptureFailedEvent for $stallId: ${e.message}")
        }
    }
}
