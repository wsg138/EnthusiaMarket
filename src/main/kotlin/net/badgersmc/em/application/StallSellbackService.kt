package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.ports.SchematicService
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
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
import kotlin.math.ceil

/**
 * Voluntary relinquish flow: owner runs `/em sellback <stall>` and
 * gets back the stall's UNOWNED state plus a refund of prepaid rent
 * minus the current period (today is non-refundable). All shops bound
 * to the stall are deleted as part of the wipe.
 *
 * Schematic restore (TDD-270/271) is intentionally NOT wired here —
 * once the schematic snapshot service ships, hook its `restore`
 * call after the ownership reset below.
 *
 * Two-step protocol used by the command layer:
 * 1. [quote] — pure read, returns refund + shop count for the
 *    confirmation prompt.
 * 2. [execute] — does the refund / state reset / wipe atomically
 *    from the caller's perspective; failures rollback the refund
 *    when possible.
 */
@Service
class StallSellbackService(
    private val stalls: StallRepository,
    private val shops: ShopRepository,
    private val offers: SellOfferRepository,
    private val economy: EconomyProvider,
    private val guildProvider: GuildProvider,
    private val config: EnthusiaMarketConfig,
    private val regionMembers: RegionMemberSync,
    private val schematics: SchematicService = SchematicService.Disabled,
    private val ipLimiter: IpLimiter,
) {

    private val log = Logger.getLogger(StallSellbackService::class.java.name)

    data class Quote(
        val stall: Stall,
        val refund: Long,
        val shopCount: Int,
        /** Number of prepaid periods being refunded — for UX clarity. */
        val refundedPeriods: Int,
    )

    sealed interface QuoteResult {
        data class Ok(val quote: Quote) : QuoteResult
        data object NotFound : QuoteResult
        data object NotAuthorised : QuoteResult
        data object NotOwned : QuoteResult
    }

    sealed interface ExecuteResult {
        data class Sold(val refund: Long, val shopsWiped: Int) : ExecuteResult
        data object NotFound : ExecuteResult
        data object NotAuthorised : ExecuteResult
        data object NotOwned : ExecuteResult
        data class Rejected(val reason: String) : ExecuteResult
    }

    fun quote(stallId: StallId, actor: UUID): QuoteResult {
        val stall = stalls.findById(stallId) ?: return QuoteResult.NotFound
        if (stall.state !in OWNERSHIP_STATES) return QuoteResult.NotOwned
        // 🔴 Guild-owned stalls: no guild-bank payout target yet → reject.
        if (stall.owner.type == OwnerType.GUILD) return QuoteResult.NotAuthorised
        if (!stall.canManage(actor, guildProvider)) return QuoteResult.NotAuthorised

        val (refund, periods) = computeRefund(stall)
        val shopCount = shops.findByStall(stallId.value).size
        return QuoteResult.Ok(Quote(stall, refund, shopCount, periods))
    }

    fun execute(stallId: StallId, actor: UUID): ExecuteResult {
        val stall = stalls.findById(stallId) ?: return ExecuteResult.NotFound
        if (stall.state !in OWNERSHIP_STATES) return ExecuteResult.NotOwned
        // 🔴 Guild-owned stalls: no guild-bank payout target yet → reject.
        if (stall.owner.type == OwnerType.GUILD) return ExecuteResult.NotAuthorised
        if (!stall.canManage(actor, guildProvider)) return ExecuteResult.NotAuthorised

        val (refund, _) = computeRefund(stall)
        val boundShops = shops.findByStall(stallId.value)
        val previousState = stall.state
        if (!reset(stall)) {
            return ExecuteResult.Rejected("Stall reset failed; contact an admin")
        }
        if (!refund(stall, actor, refund)) {
            return ExecuteResult.Rejected("Failed to deposit refund of $refund to your account")
        }
        val wiped = wipeShops(boundShops, stallId)
        clearRegion(stall)
        fireStateChanged(stallId.value, previousState, StallState.UNOWNED)
        restoreGeometry(stall)
        return ExecuteResult.Sold(refund, wiped)
    }

    private fun reset(stall: Stall): Boolean = try {
        stalls.save(stall.copy(
            state = StallState.UNOWNED,
            owner = OwnerRef.unowned(),
            ownerSince = null,
            winningBid = 0L,
            members = emptySet(),
            nextRentAt = null,
        ))
        ipLimiter.releaseStallByOwnerId(stall.owner.id)
        cleanupOffer(stall.id)
        true
    } catch (_: Exception) {
        false
    }

    private fun cleanupOffer(stallId: StallId) {
        if (offers.findByStall(stallId) == null) return
        try {
            offers.delete(stallId)
        } catch (cleanupError: Exception) {
            log.warning(
                "StallSellbackService.execute: failed to cleanup lingering sell offer for " +
                    "${stallId.value}. cause=${cleanupError.message}"
            )
        }
    }

    private fun refund(stall: Stall, actor: UUID, amount: Long): Boolean {
        if (amount <= 0 || economy.deposit(actor, amount)) return true
        try {
            stalls.save(stall)
        } catch (_: Exception) {
            // The rejection below remains operator-visible even when rollback also fails.
        }
        return false
    }

    private fun wipeShops(boundShops: List<net.badgersmc.em.domain.shop.Shop>, stallId: StallId): Int {
        var wiped = 0
        for (shop in boundShops) {
            try {
                shops.delete(shop.id)
                wiped++
            } catch (e: Exception) {
                log.warning(
                    "StallSellbackService.execute: failed to delete shop ${shop.id} " +
                        "bound to stall ${stallId.value}; continuing. cause=${e.message}"
                )
            }
        }
        return wiped
    }

    private fun clearRegion(stall: Stall) {
        try {
            regionMembers.clearOwnersAndMembers(stall.world, stall.regionId)
        } catch (e: Exception) {
            log.warning(
                "StallSellbackService: WG owner/member clear failed for stall " +
                    "${stall.id.value}. The DB owner is UNOWNED but WG perms may need a " +
                    "manual /rg removeowner. cause=${e.message}"
            )
        }
    }

    private fun restoreGeometry(stall: Stall) {
        if (!config.schematics.enabled) return
        val result = schematics.restore(stall.id.value, stall.world, stall.regionId)
        if (result is SchematicService.Result.Failure) {
            log.warning(
                "StallSellbackService.execute: schematic restore failed for stall " +
                    "${stall.id.value}; geometry left as-is. cause=${result.cause.message}"
            )
        }
    }

    /**
     * Refund = whole periods of prepayment beyond the current one,
     * priced at `rentTerms.dailyRent(winningBid)` (or the floor of 1
     * applied in the rent-extension flow).
     *
     * The "current" period (today) is non-refundable — owner already
     * had the use of it. A stall with `nextRentAt <= now` has no
     * prepayment to refund.
     */
    private fun computeRefund(stall: Stall): Pair<Long, Int> {
        val now = Instant.now()
        val nextRent = stall.nextRentAt ?: return 0L to 0
        val remaining = Duration.between(now, nextRent)
        if (remaining.isZero || remaining.isNegative) return 0L to 0

        val interval = collectionInterval()
        if (interval.isZero || interval.isNegative) return 0L to 0

        // Whole periods of prepayment (current period inclusive), then
        // subtract one for the non-refundable current period.
        // Use ceiling division to correctly count partial periods.
        val periodsTotal = ceil(remaining.seconds.toDouble() / interval.seconds).toInt()
        val refundable = (periodsTotal - 1).coerceAtLeast(0)
        if (refundable <= 0) return 0L to 0

        val perPeriod = stall.rentTerms.dailyRent(stall.winningBid)
            .let { if (stall.winningBid > 0L) maxOf(it, 1L) else it }
        return (refundable * perPeriod) to refundable
    }

    private fun collectionInterval(): Duration = try {
        Duration.parse(config.rent.collectionInterval)
    } catch (_: java.time.format.DateTimeParseException) {
        Duration.ofDays(1)
    }

    private fun fireStateChanged(stallId: String, previous: StallState, current: StallState) {
        if (previous == current) return
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                StallStateChangedEvent(stallId, previous, current)
            )
        } catch (e: Exception) {
            log.warning("Failed to fire StallStateChangedEvent for $stallId: ${e.message}")
        }
    }

    private companion object {
        val OWNERSHIP_STATES = setOf(StallState.OWNED, StallState.GRACE)
    }
}
