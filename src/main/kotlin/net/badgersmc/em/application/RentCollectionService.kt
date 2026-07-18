package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.auction.AuctionState
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.annotations.Service
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * Report from a single rent collection tick.
 */
data class RentReport(
    val defaults: Int,
    val evictions: Int,
    val errors: Int
)

/**
 * Application-layer service that periodically collects rent from stall owners,
 * marks delinquent owners as defaulted (GRACE), and evicts those past the grace period.
 */
@Service
class RentCollectionService(
    private val stallRepository: StallRepository,
    private val shops: net.badgersmc.em.domain.shop.ShopRepository,
    private val config: EnthusiaMarketConfig,
    private val auctionRepository: AuctionRepository,
    private val lang: LangService,
) {

    private val log = Logger.getLogger(RentCollectionService::class.java.name)

    private val activeStates = setOf(StallState.OWNED, StallState.GRACE)

    /**
     * Execute one rent collection tick.
     *
     * Rent is NEVER auto-charged — players pay by right-clicking the purchase sign
     * ([StallRentExtensionService.extend]). This tick only enforces the
     * grace/eviction timeline for overdue stalls:
     * 1. OWNED with nextRentAt past grace → immediate emergency auction.
     * 2. OWNED with nextRentAt recently past → GRACE (shops frozen).
     * 3. GRACE with nextRentAt past grace → emergency auction.
     *
     * @param now the current instant (injectable for testability, defaults to system clock)
     */
    fun tick(now: Instant = Instant.now()): RentReport {
        val stalls = stallRepository.all()
        var defaults = 0
        var evictions = 0
        var errors = 0

        for (stall in stalls) {
            if (stall.state !in activeStates) continue

            try {
                val result = processStall(stall, now)
                when (result) {
                    is ProcessResult.Defaulted -> defaults++
                    is ProcessResult.Evicted -> evictions++
                    is ProcessResult.Skipped -> { /* no-op */ }
                }
            } catch (e: Exception) {
                errors++
            }
        }

        return RentReport(
            defaults = defaults,
            evictions = evictions,
            errors = errors
        )
    }

    private fun processStall(stall: Stall, now: Instant): ProcessResult {
        // C-10: honour a future nextRentAt. Buyout/auction-settle/extension push
        // nextRentAt forward to pre-pay a period; without this guard the fixed-interval
        // ticker re-charges on its own schedule and the pre-paid period is lost.
        // A null nextRentAt (legacy/seeded stalls) falls through and is charged as before.
        stall.nextRentAt?.let { due -> if (now.isBefore(due)) return ProcessResult.Skipped }

        // Unowned/NONE stalls have nothing to charge.
        if (stall.owner.type == OwnerType.NONE) return ProcessResult.Skipped

        // M4: floor to >= 1 for stalls with a real buy price; admin-gifted (winningBid <= 0) stay free.
        val computed = stall.rentTerms.dailyRent(stall.winningBid)
        val rentDue = if (stall.winningBid > 0L) maxOf(computed, 1L) else computed

        // Rent is never auto-charged. Players pay rent by right-clicking the
        // purchase sign (StallRentExtensionService.extend). The scheduler only
        // enforces the grace/eviction timeline for overdue stalls.
        return handleFailure(stall, now, rentDue)
    }

    /** OWNED with nextRentAt past grace goes straight to emergency auction
     *  (no point waiting another 3d after boot). Otherwise OWNED → GRACE.
     *  GRACE past its window starts emergency auction. */
    private fun handleFailure(stall: Stall, now: Instant, rentDue: Long): ProcessResult {
        return when (stall.state) {
            StallState.OWNED -> {
                val due = stall.nextRentAt
                if (due != null && isPastGrace(due, now)) {
                    // Stall already >3d past due — skip GRACE, fire emergency auction immediately.
                    // Shops must be frozen first — GRACE normally does this but we're bypassing it.
                    shops.freezeByStall(stall.id.value, frozen = true)
                    return emergencyAuction(stall, now, rentDue)
                }
                // Freeze shops FIRST: if it fails, the stall stays OWNED and the next
                // tick retries. If we save GRACE first and the freeze throws, the stall
                // is in GRACE with active shops — the GRACE processing branch does not
                // re-freeze, so the eviction penalty would be broken for the entire
                // grace period.
                shops.freezeByStall(stall.id.value, frozen = true)
                // Preserve original ownerSince — do NOT reset it so the audit trail stays intact.
                // Anchor nextRentAt so the grace window starts from now, not the original purchase
                // date (which could be months ago and would cause instant eviction on next tick).
                stallRepository.save(stall.copy(
                    state = StallState.GRACE,
                    nextRentAt = stall.nextRentAt ?: now
                ))
                ProcessResult.Defaulted
            }
            StallState.GRACE -> {
                // Use nextRentAt (when rent was actually due) as the grace window start,
                // NOT ownerSince (which could be from original purchase months ago).
                val graceStart = stall.nextRentAt ?: stall.ownerSince
                if (graceStart != null && isPastGrace(graceStart, now)) emergencyAuction(stall, now, rentDue)
                else ProcessResult.Skipped
            }
            else -> ProcessResult.Skipped
        }
    }

    /** Start an emergency auction for a stall whose grace period expired.
     *  Shops stay frozen (already set on GRACE entry). The starting bid
     *  is the one-period rent due. Does NOT delete shops or clear WG —
     *  the auction winner inherits the stall with all bound shops. */
    private fun emergencyAuction(stall: Stall, now: Instant, rentDue: Long): ProcessResult {
        val startingBid = maxOf(rentDue, 1L)
        val auction = Auction(
            id = AuctionId(UUID.randomUUID().toString()),
            stallId = stall.id,
            state = AuctionState.OPEN,
            startAt = now,
            endAt = now.plus(auctionDuration()),
            startingBid = startingBid,
            highBid = null,
            antiSnipeWindow = config.auction.antiSnipeWindowDuration,
            antiSnipeExtension = config.auction.antiSnipeExtensionDuration,
        )
        // Save stall FIRST: if auction creation fails, stall is EMERGENCY_AUCTIONING without an auction
        // (admin must manually create one). This prevents duplicate auctions on retry — the stall
        // won't be processed again once it leaves GRACE/OWNED activeStates.
        stallRepository.save(stall.copy(state = StallState.EMERGENCY_AUCTIONING))
        auctionRepository.create(auction)
        try {
            Bukkit.broadcast(lang.msg("purchase_sign.msg.emergency_auction_alert",
                "stall" to stall.id.value, "bid" to startingBid))
        } catch (e: Exception) {
            // Broadcast is best-effort; don't let it roll back the auction creation.
            log.warning("Emergency auction broadcast failed for stall ${stall.id.value}: ${e.message}")
        }
        return ProcessResult.Evicted  // reuse Evicted for counting
    }

    private fun auctionDuration(): Duration = try {
        Duration.parse(config.auction.defaultDuration)
            .takeIf { !it.isZero && !it.isNegative }
            ?: Duration.ofDays(1)
    } catch (_: Exception) {
        Duration.ofDays(1)
    }

    private fun isPastGrace(graceStartedAt: Instant, now: Instant): Boolean {
        val deadline = graceStartedAt.plus(RentTimingPolicy.gracePeriod(config))
        return now.isAfter(deadline)
    }

    private sealed class ProcessResult {
        data object Defaulted : ProcessResult()
        data object Evicted : ProcessResult()
        data object Skipped : ProcessResult()
    }
}
