package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.ports.SchematicService
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.annotations.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * Report from a single rent collection tick.
 */
data class RentReport(
    val collected: Int,
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
    private val offers: SellOfferRepository,
    private val economy: EconomyProvider,
    private val guildProvider: GuildProvider,
    private val config: EnthusiaMarketConfig,
    private val regionMembers: RegionMemberSync,
    private val schematics: SchematicService = SchematicService.Disabled,
) {

    private val log = java.util.logging.Logger.getLogger(RentCollectionService::class.java.name)

    private val activeStates = setOf(StallState.OWNED, StallState.GRACE)

    /**
     * Execute one rent collection tick.
     *
     * For each stall in OWNED or GRACE state:
     * 1. Compute rent due using [Stall]'s [RentTerms.dailyRent] method.
     * 2. If SOLO owner: attempt to withdraw from their economy account.
     * 3. If withdraw succeeds and stall is GRACE, restore to OWNED.
     * 4. If withdraw fails and stall is OWNED: mark as GRACE (default).
     * 5. If withdraw fails and stall is GRACE and grace period has elapsed: evict.
     * 6. GUILD owners are skipped (no guild bank integration yet).
     *
     * @param now the current instant (injectable for testability, defaults to system clock)
     */
    fun tick(now: Instant = Instant.now()): RentReport {
        val stalls = stallRepository.all()
        var collected = 0
        var defaults = 0
        var evictions = 0
        var errors = 0

        for (stall in stalls) {
            if (stall.state !in activeStates) continue

            try {
                val result = processStall(stall, now)
                when (result) {
                    is ProcessResult.Collected -> collected++
                    is ProcessResult.Defaulted -> defaults++
                    is ProcessResult.Evicted -> evictions++
                    is ProcessResult.Skipped -> { /* no-op */ }
                }
            } catch (e: Exception) {
                errors++
            }
        }

        return RentReport(
            collected = collected,
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

        // null = skip: a corrupt SOLO owner id shouldn't evict on bad data.
        val withdrawSuccess = chargeRent(stall, rentDue) ?: return ProcessResult.Skipped
        return if (withdrawSuccess) collect(stall, now) else handleFailure(stall, now)
    }

    /**
     * Charge one rent period to the stall's owner: SOLO → personal economy,
     * GUILD → guild bank. Returns the withdraw outcome, or null when the SOLO
     * owner id is corrupt so the caller skips (rather than evicts) on bad data.
     */
    private fun chargeRent(stall: Stall, rentDue: Long): Boolean? = when (stall.owner.type) {
        OwnerType.SOLO -> {
            val ownerUuid = runCatching { UUID.fromString(stall.owner.id) }.getOrNull()
            if (ownerUuid == null) null else rentDue <= 0L || economy.withdraw(ownerUuid, rentDue)
        }
        OwnerType.GUILD -> rentDue <= 0L || guildProvider.bankWithdraw(stall.owner.id, rentDue)
        OwnerType.NONE -> null // unreachable (guarded in processStall)
    }

    /** Payment succeeded — advance the rent timer and, if in GRACE, restore to OWNED. */
    private fun collect(stall: Stall, now: Instant): ProcessResult {
        val nextRent = now.plus(collectionInterval())
        if (stall.state == StallState.GRACE) {
            stallRepository.save(stall.copy(state = StallState.OWNED, ownerSince = now, nextRentAt = nextRent))
        } else {
            stallRepository.save(stall.copy(nextRentAt = nextRent))
        }
        return ProcessResult.Collected
    }

    /** Payment failed — OWNED defaults into GRACE; GRACE past its window is evicted. */
    private fun handleFailure(stall: Stall, now: Instant): ProcessResult = when (stall.state) {
        StallState.OWNED -> {
            // First failure — move to GRACE (defaulted), start grace timer.
            stallRepository.save(stall.copy(state = StallState.GRACE, ownerSince = Instant.now()))
            ProcessResult.Defaulted
        }
        StallState.GRACE -> {
            val ownerSince = stall.ownerSince
            if (ownerSince != null && isPastGrace(ownerSince, now)) evict(stall) else ProcessResult.Skipped
        }
        else -> ProcessResult.Skipped
    }

    /** Clear DB ownership + WG perms so the evicted owner immediately loses build rights. */
    private fun evict(stall: Stall): ProcessResult {
        stallRepository.save(
            stall.copy(
                state = StallState.UNOWNED,
                owner = OwnerRef.unowned(),
                ownerSince = null,
                winningBid = 0L,
                nextRentAt = null,
            )
        )
        cleanupAfterEviction(stall)
        return ProcessResult.Evicted
    }

    /** Best-effort post-eviction cleanup: drop lingering offer, clear WG, restore geometry. */
    private fun cleanupAfterEviction(stall: Stall) {
        // M3 — drop any lingering sell offer on the now-UNOWNED stall so a follow-up
        // click doesn't trip the offer-mutex check (best-effort, logged, never re-thrown).
        if (offers.findByStall(stall.id) != null) {
            try {
                offers.delete(stall.id)
            } catch (cleanupErr: Exception) {
                log.warning(
                    "RentCollectionService: failed to cleanup lingering sell offer for " +
                        "${stall.id.value}. cause=${cleanupErr.message}"
                )
            }
        }
        try {
            regionMembers.clearOwnersAndMembers(stall.world, stall.regionId)
        } catch (_: Exception) {
            // DB is authoritative; WG can be resynced via /em rg resync. Eviction stands.
        }
        // REQ-271 — restore the pre-claim geometry now the stall is UNOWNED. Best-effort:
        // a failed paste must not roll back the eviction (REQ-272/273). Gated on schematics.enabled.
        if (config.schematics.enabled) {
            val restore = schematics.restore(stall.id.value, stall.world, stall.regionId)
            if (restore is SchematicService.Result.Failure) {
                log.warning(
                    "Eviction: schematic restore failed for stall ${stall.id.value}; " +
                        "geometry left as-is. cause=${restore.cause.message}"
                )
            }
        }
    }

    private fun collectionInterval(): Duration = try {
        Duration.parse(config.rent.collectionInterval)
            .takeIf { !it.isZero && !it.isNegative }
            ?: Duration.ofDays(1)
    } catch (_: java.time.format.DateTimeParseException) {
        Duration.ofDays(1)
    }

    private fun isPastGrace(ownerSince: Instant, now: Instant): Boolean {
        val graceDuration = try {
            Duration.parse(config.rent.gracePeriod)
        } catch (e: java.time.format.DateTimeParseException) {
            // Invalid config — fall back to 3-day default
            Duration.ofDays(3)
        }
        val deadline = ownerSince.plus(graceDuration)
        return now.isAfter(deadline)
    }

    private sealed class ProcessResult {
        data object Collected : ProcessResult()
        data object Defaulted : ProcessResult()
        data object Evicted : ProcessResult()
        data object Skipped : ProcessResult()
    }
}