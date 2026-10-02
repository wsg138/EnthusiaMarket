package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.auction.AuctionState
import net.badgersmc.em.domain.ports.MarketMutationGate
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.ports.SchematicService
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
    private val regionMembers: RegionMemberSync,
    private val ipLimiter: IpLimiter,
    private val mutationGate: MarketMutationGate = MarketMutationGate.Open,
    private val schematics: SchematicService = SchematicService.Disabled,
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
        // Recover stalls stuck in EMERGENCY_AUCTIONING with no OPEN auction.
        // Fixed by PR #182 (emergency revert paths now clear owner), but stalls
        // that were stuck before the fix was deployed are still orphaned.
        // Run BEFORE the tick loop so a transient auction-write failure during
        // emergencyAuction() this tick doesn't get mistaken for an orphan.
        val recovered = recoverOrphanedEmergencyStalls()
        if (recovered > 0) {
            log.info("RentCollectionService: recovered $recovered orphaned emergency-auction stall(s)")
        }

        val stalls = stallRepository.all()
        var defaults = 0
        var evictions = 0
        var errors = 0

        for (stall in stalls) {
            if (stall.state !in activeStates) continue
            if (mutationGate.isStallLocked(stall.id.value)) continue

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

    /** Start a clean emergency auction for a stall whose grace period expired.
     *  The former owner remains on the stall only as seller provenance for
     *  settlement; all effective ownership projections are removed after the
     *  authoritative EMERGENCY_AUCTIONING save succeeds. */
    private fun emergencyAuction(stall: Stall, now: Instant, rentDue: Long): ProcessResult {
        val startingBid = maxOf(rentDue, 1L)
        val duration = auctionDuration()
        val auction = Auction(
            id = AuctionId(UUID.randomUUID().toString()),
            stallId = stall.id,
            state = AuctionState.OPEN,
            startAt = now,
            // Timer starts on the first bid — keep the auction open
            // indefinitely until someone participates. Instant.MAX cannot be
            // serialised to epoch millis (overflows Long), so use the
            // maximum representable instant instead.
            endAt = Instant.ofEpochMilli(Long.MAX_VALUE),
            startingBid = startingBid,
            highBid = null,
            antiSnipeWindow = config.auction.antiSnipeWindowDuration,
            antiSnipeExtension = config.auction.antiSnipeExtensionDuration,
            auctionDuration = duration,
        )
        // Save FIRST so PR #194's optimistic moderation fence remains
        // authoritative. Destructive cleanup is forbidden until this succeeds.
        val forfeited = stall.copy(
            state = StallState.EMERGENCY_AUCTIONING,
            members = emptySet(),
        )
        stallRepository.save(forfeited)

        // Broadcast before auction creation so players still receive the alert
        // if the auction write itself fails after forfeiture was persisted.
        try {
            Bukkit.broadcast(lang.msg("purchase_sign.msg.emergency_auction_alert",
                "stall" to stall.id.value, "bid" to startingBid))
        } catch (e: Exception) {
            log.warning("Emergency auction broadcast failed for stall ${stall.id.value}: ${e.message}")
        }
        auctionRepository.create(auction)
        cleanupEmergencyForfeiture(stall)
        return ProcessResult.Evicted  // reuse Evicted for counting
    }

    private fun cleanupEmergencyForfeiture(stall: Stall) {
        if (stall.owner.type != OwnerType.NONE && stall.owner.id.isNotBlank()) {
            ipLimiter.releaseStallByOwnerId(stall.owner.id)
        }

        try {
            for (shop in shops.findByStall(stall.id.value)) {
                if (shop.adminShop) continue
                try {
                    shops.delete(shop.id)
                } catch (failure: Exception) {
                    log.warning(
                        "Emergency auction: failed to delete shop ${shop.id} for ${stall.id.value}: " +
                            failure.message
                    )
                }
            }
        } catch (failure: Exception) {
            log.warning(
                "Emergency auction: failed to enumerate shops for ${stall.id.value}: ${failure.message}"
            )
        }

        try {
            regionMembers.clearOwnersAndMembers(stall.world, stall.regionId)
        } catch (failure: Exception) {
            log.warning(
                "Emergency auction: failed to clear region access for ${stall.id.value}: ${failure.message}"
            )
        }

        if (config.schematics.enabled) {
            try {
                val restore = schematics.restore(stall.id.value, stall.world, stall.regionId)
                if (restore is SchematicService.Result.Failure) {
                    log.warning(
                        "Emergency auction: schematic restore failed for ${stall.id.value}: " +
                            restore.cause.message
                    )
                }
            } catch (failure: Exception) {
                log.warning(
                    "Emergency auction: schematic restore threw for ${stall.id.value}: ${failure.message}"
                )
            }
        }
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

    /** Recover stalls stuck in EMERGENCY_AUCTIONING whose auction was closed
     *  without reverting the stall (pre-#182 bug). Returns count of recovered
     *  stalls. Idempotent — stalls with an active OPEN auction are skipped. */
    private fun recoverOrphanedEmergencyStalls(): Int {
        var recovered = 0
        for (stall in stallRepository.all()) {
            val eligible = stall.state == StallState.EMERGENCY_AUCTIONING &&
                !mutationGate.isStallLocked(stall.id.value)
            if (eligible && recoverOrphanedEmergencyStall(stall)) {
                recovered++
            }
        }
        return recovered
    }

    private fun recoverOrphanedEmergencyStall(stall: Stall): Boolean {
        return try {
            if (auctionRepository.findOpenByStall(stall.id) != null) {
                false
            } else {
                // Persist the authoritative state first. PR #194's repository
                // fence can still reject a moderation race after the fast gate;
                // no destructive projection cleanup may happen before this save.
                stallRepository.save(stall.releaseOwnership())

                cleanupEmergencyForfeiture(stall)
                // Preserved admin shops may have been frozen while the previous
                // ownership context was in GRACE / emergency auction.
                shops.freezeByStall(stall.id.value, frozen = false)
                true
            }
        } catch (e: Exception) {
            log.warning(
                "RentCollectionService: failed to recover orphaned emergency stall " +
                    "${stall.id.value}: ${e.message}"
            )
            false
        }
    }

    private sealed class ProcessResult {
        data object Defaulted : ProcessResult()
        data object Evicted : ProcessResult()
        data object Skipped : ProcessResult()
    }
}
