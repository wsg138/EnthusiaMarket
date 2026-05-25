package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.annotations.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

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
    private val economy: EconomyProvider,
    private val config: EnthusiaMarketConfig
) {

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
     */
    fun tick(): RentReport {
        val stalls = stallRepository.all()
        var collected = 0
        var defaults = 0
        var evictions = 0
        var errors = 0

        for (stall in stalls) {
            if (stall.state !in activeStates) continue

            try {
                val result = processStall(stall)
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

    private fun processStall(stall: Stall): ProcessResult {
        // Skip non-player-owned stalls (guild bank integration not yet available)
        if (stall.owner.type != OwnerType.SOLO) {
            return ProcessResult.Skipped
        }

        val ownerUuid = try {
            UUID.fromString(stall.owner.id)
        } catch (_: IllegalArgumentException) {
            return ProcessResult.Skipped
        }
        val rentDue = stall.rentTerms.dailyRent(stall.winningBid)

        val withdrawSuccess = economy.withdraw(ownerUuid, rentDue)

        if (withdrawSuccess) {
            // Payment succeeded — if in GRACE, restore to OWNED
            if (stall.state == StallState.GRACE) {
                stallRepository.save(
                    stall.copy(state = StallState.OWNED, ownerSince = Instant.now())
                )
            } else {
                // Already OWNED, just persist (ownerSince unchanged)
                stallRepository.save(stall)
            }
            return ProcessResult.Collected
        }

        // Payment failed
        return when (stall.state) {
            StallState.OWNED -> {
                // First failure — move to GRACE (defaulted), start grace timer
                stallRepository.save(
                    stall.copy(state = StallState.GRACE, ownerSince = Instant.now())
                )
                ProcessResult.Defaulted
            }
            StallState.GRACE -> {
                // Already in GRACE — check if grace period has elapsed
                val ownerSince = stall.ownerSince
                if (ownerSince != null && isPastGrace(ownerSince)) {
                    // Evict the stall
                    stallRepository.save(
                        stall.copy(
                            state = StallState.UNOWNED,
                            owner = OwnerRef.unowned(),
                            ownerSince = null,
                            winningBid = 0L
                        )
                    )
                    ProcessResult.Evicted
                } else {
                    // Grace not yet expired — do nothing
                    ProcessResult.Skipped
                }
            }
            else -> ProcessResult.Skipped
        }
    }

    private fun isPastGrace(ownerSince: Instant): Boolean {
        val graceDuration = try {
            Duration.parse(config.rent.gracePeriod)
        } catch (e: java.time.format.DateTimeParseException) {
            // Invalid config — fall back to 3-day default
            Duration.ofDays(3)
        }
        val deadline = ownerSince.plus(graceDuration)
        return Instant.now().isAfter(deadline)
    }

    private sealed class ProcessResult {
        data object Collected : ProcessResult()
        data object Defaulted : ProcessResult()
        data object Evicted : ProcessResult()
        data object Skipped : ProcessResult()
    }
}