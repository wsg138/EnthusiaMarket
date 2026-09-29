package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.MarketAcquisitionBlockedException
import net.badgersmc.em.domain.ports.MarketModerationPolicy
import net.badgersmc.em.domain.ports.MarketMutationGate
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import java.time.Clock
import java.util.UUID
import java.util.logging.Logger

/** Orchestrates outright click-to-buy stall purchases (REQ-250). */
@Service
@Suppress("LongParameterList")
class StallBuyoutService(
    private val stalls: StallRepository,
    private val offers: SellOfferRepository,
    private val auctions: net.badgersmc.em.domain.auction.AuctionRepository,
    private val economy: EconomyProvider,
    private val config: EnthusiaMarketConfig,
    private val guildProvider: GuildProvider,
    private val regionMembers: RegionMemberSync,
    private val limits: LimitResolutionService,
    private val ownership: StallOwnershipCounter,
    private val ipLimiter: IpLimiter,
    private val moderationPolicy: MarketModerationPolicy = MarketModerationPolicy.AllowAll,
    private val mutationGate: MarketMutationGate = MarketMutationGate.Open,
) {
    private val log = Logger.getLogger(StallBuyoutService::class.java.name)

    internal var clock: Clock = Clock.systemUTC()

    sealed interface Result {
        data class Purchased(val stall: Stall, val price: Long, val owner: OwnerRef) : Result
        data object NotFound : Result
        data object AuctionLive : Result
        data object AlreadyOwned : Result
        data object NotInGuild : Result
        data object NoGuildPermission : Result
        data class Rejected(val reason: String) : Result
    }

    private data class BuyRequest(
        val stallId: StallId,
        val payer: UUID,
        val owner: OwnerRef,
        val price: Long,
        val ip: String,
    )

    fun buy(stallId: StallId, buyer: UUID, price: Long, ip: String): Result =
        buyForOwner(BuyRequest(stallId, buyer, OwnerRef.solo(buyer), price, ip))

    fun buyForGuild(stallId: StallId, actor: UUID, price: Long, ip: String): Result {
        val guild = guildProvider.guildOf(actor) ?: return Result.NotInGuild
        if (!guildProvider.hasShopPermission(actor, guild.id, GuildProvider.GuildPermission.MANAGE_SHOPS)) {
            return Result.NoGuildPermission
        }
        return buyForOwner(BuyRequest(stallId, actor, OwnerRef.guild(guild.id), price, ip))
    }

    private fun enforceLimit(request: BuyRequest, stall: Stall): Result? {
        if (request.owner.type != OwnerType.SOLO) return null
        val counts = ownership.counts(request.payer)
        return when (
            val decision = limits.canClaim(
                request.payer,
                stall.kind,
                counts.total,
                counts.byKind[stall.kind] ?: 0,
            )
        ) {
            is LimitResolutionService.ClaimDecision.Rejected.TotalCapReached ->
                Result.Rejected("Stall limit reached (${decision.cap})")
            is LimitResolutionService.ClaimDecision.Rejected.KindCapReached ->
                Result.Rejected("Limit reached for ${decision.kind} stalls (${decision.cap})")
            LimitResolutionService.ClaimDecision.Allowed -> null
        }
    }

    private fun refundAfterFailedAward(request: BuyRequest, cause: Exception) {
        val refunded = refundPayer(request)
        logFailedAward(request, cause, refunded)
    }

    private fun refundPayer(request: BuyRequest): Boolean = try {
        economy.deposit(request.payer, request.price)
    } catch (refund: Exception) {
        log.severe("StallBuyoutService: refund of ${request.price} to ${request.payer} threw: ${refund.message}")
        false
    }

    private fun logFailedAward(request: BuyRequest, cause: Exception, refunded: Boolean) {
        val stallId = request.stallId.value
        val message = if (refunded) {
            "StallBuyoutService: ownership transfer failed for stall $stallId after charging " +
                "payer ${request.payer} price=${request.price} (owner=${request.owner}). " +
                "Payer has been refunded. cause=${cause.message}"
        } else {
            "StallBuyoutService: ownership transfer failed for stall $stallId AND the refund of " +
                "${request.price} to ${request.payer} failed — manual refund required. cause=${cause.message}"
        }
        log.severe(message)
    }

    private fun isAuctionLive(stall: Stall, stallId: StallId): Boolean =
        stall.state in setOf(StallState.AUCTIONING, StallState.RE_AUCTIONING, StallState.EMERGENCY_AUCTIONING) ||
            auctions.findOpenByStall(stallId) != null

    private fun validatePurchase(stall: Stall, request: BuyRequest): Result? {
        if (request.price <= 0) return Result.Rejected("Sign price is invalid")
        if (isAuctionLive(stall, request.stallId)) return Result.AuctionLive
        if (stall.state != StallState.UNOWNED) return Result.AlreadyOwned
        directBuyDelayRejection(request.stallId)?.let { return it }
        return enforceLimit(request, stall)
    }

    private fun directBuyDelayRejection(stallId: StallId): Result.Rejected? {
        if (config.auction.directBuyDelaySeconds <= 0) return null
        val recentClosed = auctions.findMostRecentClosedByStall(stallId) ?: return null
        val allowedAt = recentClosed.endAt.plusSeconds(config.auction.directBuyDelaySeconds)
        return if (clock.instant() < allowedAt) {
            Result.Rejected("Direct purchase opens after the auction window ends")
        } else {
            null
        }
    }

    private fun buyForOwner(request: BuyRequest): Result = try {
        moderationPolicy.withAcquisitionPermit(request.payer) { buyForOwnerWithPermit(request) }
    } catch (blocked: MarketAcquisitionBlockedException) {
        Result.Rejected(blocked.message ?: "Market acquisitions are restricted")
    }

    private fun buyForOwnerWithPermit(request: BuyRequest): Result {
        if (mutationGate.isStallLocked(request.stallId.value)) {
            return Result.Rejected("This stall is temporarily unavailable")
        }
        val stall = stalls.findById(request.stallId) ?: return Result.NotFound
        validatePurchase(stall, request)?.let { return it }

        val reservation = ipLimiter.acquireStall(request.ip, request.owner.id)
        if (!reservation.allowed) return Result.Rejected("Your IP already owns a stall.")
        var completed = false
        try {
            val result = executePurchase(stall, request)
            completed = result is Result.Purchased
            return result
        } finally {
            if (!completed) ipLimiter.rollback(reservation.reservation)
        }
    }

    private fun executePurchase(stall: Stall, request: BuyRequest): Result {
        if (!economy.withdraw(request.payer, request.price)) {
            return Result.Rejected("Insufficient funds: ${request.price} required")
        }
        val updated = persistAward(stall, request)
        cleanupLingeringOffer(request.stallId)
        syncRegionOwnership(updated, request)
        fireStateChanged(request.stallId.value, stall.state, updated.state)
        return Result.Purchased(updated, request.price, request.owner)
    }

    private fun persistAward(stall: Stall, request: BuyRequest): Stall {
        try {
            val now = clock.instant()
            val awarded = stall.awardTo(
                request.owner,
                request.price,
                now,
                now.plus(RentTimingPolicy.collectionInterval(config)),
            )
            stalls.save(awarded)
            return awarded
        } catch (failure: Exception) {
            refundAfterFailedAward(request, failure)
            throw failure
        }
    }

    private fun cleanupLingeringOffer(stallId: StallId) {
        if (offers.findByStall(stallId) == null) return
        try {
            offers.delete(stallId)
        } catch (failure: Exception) {
            log.warning(
                "StallBuyoutService: failed to cleanup lingering sell offer for " +
                    "${stallId.value}. cause=${failure.message}",
            )
        }
    }

    private fun syncRegionOwnership(stall: Stall, request: BuyRequest) {
        try {
            when (request.owner.type) {
                OwnerType.SOLO -> regionMembers.setOwner(
                    stall.world,
                    stall.regionId,
                    UUID.fromString(request.owner.id),
                )
                OwnerType.GUILD -> syncGuildRegion(stall, request.owner.id)
                OwnerType.NONE -> Unit
            }
        } catch (failure: Exception) {
            log.warning(
                "StallBuyoutService: WG owner sync failed for stall ${request.stallId.value} " +
                    "(owner=${request.owner}). The DB owner is correct; players may need op until " +
                    "the region is resynced. cause=${failure.message}",
            )
        }
    }

    private fun syncGuildRegion(stall: Stall, guildId: String) {
        regionMembers.clearOwnersAndMembers(stall.world, stall.regionId)
        val memberIds = guildProvider.memberIds(guildId)
        if (memberIds.isNotEmpty()) {
            regionMembers.syncGuildMembers(stall.world, stall.regionId, memberIds)
            return
        }
        log.warning(
            "StallBuyoutService: stall ${stall.id.value} awarded to guild $guildId " +
                "but no online guild members found — region owners/members cleared; " +
                "members will gain access when /em rg resync runs with them online.",
        )
    }

    private fun fireStateChanged(stallId: String, previous: StallState, current: StallState) {
        if (previous == current) return
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(StallStateChangedEvent(stallId, previous, current))
        } catch (failure: Exception) {
            log.warning("Failed to fire StallStateChangedEvent for $stallId: ${failure.message}")
        }
    }
}
