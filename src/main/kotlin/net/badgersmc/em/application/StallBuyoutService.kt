package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
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
) {

    private val log = Logger.getLogger(StallBuyoutService::class.java.name)

    /** Injectable clock for deterministic time-travel in tests. */
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

    /**
     * Buy the stall for [buyer] personally. Charges + awards to a SOLO
     * owner ref. Convenience overload over [buyForOwner].
     */
    fun buy(stallId: StallId, buyer: UUID, price: Long, ip: String): Result =
        buyForOwner(stallId, payer = buyer, owner = OwnerRef.solo(buyer), price = price, ip = ip)

    /**
     * Buy the stall on behalf of [actor]'s current guild. [actor] is
     * charged personally (the guild bank isn't a UUID-addressable
     * economy account in the current Vault setup); the stall is
     * awarded to OwnerRef.guild. Requires the actor to be a guild
     * member with the MANAGE_SHOPS permission so randoms can't bind
     * a stall to a guild they don't have authority over.
     */
    fun buyForGuild(stallId: StallId, actor: UUID, price: Long, ip: String): Result {
        val guild = guildProvider.guildOf(actor) ?: return Result.NotInGuild
        if (!guildProvider.hasShopPermission(
                actor,
                guild.id,
                GuildProvider.GuildPermission.MANAGE_SHOPS,
            )
        ) {
            return Result.NoGuildPermission
        }
        // WG owner sync (including the GUILD skip) is handled inside buyForOwner.
        return buyForOwner(stallId, payer = actor, owner = OwnerRef.guild(guild.id), price = price, ip = ip)
    }

    /**
     * Personal-ownership limit gate. Guild buys route here with `owner.type == GUILD` and skip it
     * (a guild claim is not a personal claim). Counts SOLO-owned stalls only. Returns a rejecting
     * [Result] when the player is at a cap, or null when the claim is allowed.
     */
    private fun enforceLimit(owner: OwnerRef, payer: UUID, stall: net.badgersmc.em.domain.stall.Stall): Result? {
        if (owner.type != OwnerType.SOLO) return null
        val counts = ownership.counts(payer)
        return when (val decision = limits.canClaim(payer, stall.kind, counts.total, counts.byKind[stall.kind] ?: 0)) {
            is LimitResolutionService.ClaimDecision.Rejected.TotalCapReached ->
                Result.Rejected("Stall limit reached (${decision.cap})")
            is LimitResolutionService.ClaimDecision.Rejected.KindCapReached ->
                Result.Rejected("Limit reached for ${decision.kind} stalls (${decision.cap})")
            LimitResolutionService.ClaimDecision.Allowed -> null
        }
    }

    /**
     * Refund the buyer after a persistence failure that left them charged for a stall they never
     * got. Best-effort: if the refund itself throws, log loudly — the caller always rethrows the
     * ORIGINAL persistence exception (the root cause), never the deposit error.
     */
    private fun refundAfterFailedAward(payer: UUID, price: Long, stallId: StallId, owner: OwnerRef, cause: Exception) {
        // economy.deposit returns false on failure (and may also throw), so check both.
        val refunded = try {
            economy.deposit(payer, price)
        } catch (refund: Exception) {
            log.severe("StallBuyoutService: refund of $price to $payer threw: ${refund.message}")
            false
        }
        if (refunded) {
            log.severe(
                "StallBuyoutService: ownership transfer failed for stall ${stallId.value} after charging " +
                    "payer $payer price=$price (owner=$owner). Payer has been refunded. cause=${cause.message}"
            )
        } else {
            log.severe(
                "StallBuyoutService: ownership transfer failed for stall ${stallId.value} AND the refund of " +
                    "$price to $payer failed — manual refund required. cause=${cause.message}"
            )
        }
    }

    /** The stall is mid-auction (initial one-shot or re-auction), so click-to-buy must defer. */
    private fun isAuctionLive(stall: net.badgersmc.em.domain.stall.Stall, stallId: StallId): Boolean =
        stall.state in setOf(StallState.AUCTIONING, StallState.RE_AUCTIONING, StallState.EMERGENCY_AUCTIONING) ||
            auctions.findOpenByStall(stallId) != null

    /**
     * Run all validation gates before committing a purchase. Returns a rejecting
     * [Result] if any gate fails, or null when the purchase is allowed to proceed.
     * Extracted from [buyForOwner] to keep complexity within static analysis limits.
     */
    private fun validatePurchase(
        stall: Stall,
        stallId: StallId,
        owner: OwnerRef,
        payer: UUID,
        price: Long,
        ip: String,
    ): Result? {
        if (price <= 0) return Result.Rejected("Sign price is invalid")

        if (isAuctionLive(stall, stallId)) return Result.AuctionLive

        if (stall.state != StallState.UNOWNED) {
            return Result.AlreadyOwned
        }

        if (config.auction.directBuyDelaySeconds > 0) {
            val recentClosed = auctions.findMostRecentClosedByStall(stallId)
            if (recentClosed != null) {
                val allowedAt = recentClosed.endAt.plusSeconds(config.auction.directBuyDelaySeconds)
                if (clock.instant() < allowedAt) {
                    return Result.Rejected("Direct purchase opens after the auction window ends")
                }
            }
        }

        enforceLimit(owner, payer, stall)?.let { return it }

        if (!ipLimiter.tryClaimStall(ip, owner.id)) {
            return Result.Rejected("Your IP already owns a stall.")
        }

        return null
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun buyForOwner(stallId: StallId, payer: UUID, owner: OwnerRef, price: Long, ip: String): Result {
        val stall = stalls.findById(stallId) ?: return Result.NotFound

        validatePurchase(stall, stallId, owner, payer, price, ip)?.let { return it }

        if (!economy.withdraw(payer, price)) {
            return Result.Rejected("Insufficient funds: $price required")
        }

        val previousState = stall.state
        val updated = try {
            val now = clock.instant()
            val awarded = stall.awardTo(owner, price, now, now.plus(RentTimingPolicy.collectionInterval(config)))
            stalls.save(awarded)
            // Defensive: if a sell offer somehow lingered on an UNOWNED
            // stall, clean it up so a follow-up click doesn't trip the
            // mutex check next door.
            if (offers.findByStall(stallId) != null) {
                try {
                    offers.delete(stallId)
                } catch (cleanupErr: Exception) {
                    log.warning(
                        "StallBuyoutService: failed to cleanup lingering sell offer for " +
                            "${stallId.value}. cause=${cleanupErr.message}"
                    )
                }
            }
            awarded
        } catch (e: Exception) {
            refundAfterFailedAward(payer, price, stallId, owner, e)
            throw e
        }

        // Sync ownership to WorldGuard so the new owner can actually
        // build / break / interact inside the region without being op.
        // SOLO → WG owner = buyer UUID. GUILD → can't map a guild to
        // a WG player UUID directly; log + skip. Operators can wire
        // a LumaGuilds → WG bridge later. Failures are logged but
        // don't roll back the purchase — the DB owner remains the
        // canonical source of truth and a resync command can be added.
        try {
            when (owner.type) {
                OwnerType.SOLO -> regionMembers.setOwner(
                    updated.world, updated.regionId, java.util.UUID.fromString(owner.id)
                )
                OwnerType.GUILD -> {
                    regionMembers.clearOwnersAndMembers(updated.world, updated.regionId)
                    val guids = guildProvider.memberIds(owner.id)
                    if (guids.isNotEmpty()) {
                        regionMembers.syncGuildMembers(updated.world, updated.regionId, guids)
                    } else {
                        log.warning(
                            "StallBuyoutService: stall ${stallId.value} awarded to guild ${owner.id} " +
                                "but no online guild members found — region owners/members cleared; " +
                                "members will gain access when /em rg resync runs with them online."
                        )
                    }
                }
                OwnerType.NONE -> Unit // unreachable; awardTo rejects NONE.
            }
        } catch (e: Exception) {
            log.warning(
                "StallBuyoutService: WG owner sync failed for stall ${stallId.value} " +
                    "(owner=$owner). The DB owner is correct; players may need op until " +
                    "the region is resynced. cause=${e.message}"
            )
        }

        // C6: remove the OUTER guild WG sync that was previously in
        // buyForGuild — the inner block above correctly handles GUILD
        // by logging+skipping. The outer call tried UUID.fromString on
        // the guild id, which never resolves to a real player UUID.

        fireStateChanged(stallId.value, previousState, updated.state)
        return Result.Purchased(updated, price, owner)
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
