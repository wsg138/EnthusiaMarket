package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.MarketMutationGate
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import java.time.Duration
import java.time.Instant
import java.util.logging.Logger

data class BulkRentExtensionReport(
    val updated: Int,
    val recovered: Int,
    val skipped: Int,
    val failed: Int,
)

@Service
class BulkRentExtensionService(
    private val stalls: StallRepository,
    private val config: EnthusiaMarketConfig,
    private val mutationGate: MarketMutationGate = MarketMutationGate.Open,
) {
    private val log = Logger.getLogger(BulkRentExtensionService::class.java.name)

    fun extendAll(duration: Duration, now: Instant): BulkRentExtensionReport {
        require(!duration.isZero && !duration.isNegative) { "Extension duration must be positive" }

        var updated = 0
        var recovered = 0
        var skipped = 0
        var failed = 0

        for (stall in stalls.all()) {
            if (stall.state != StallState.OWNED && stall.state != StallState.GRACE) {
                skipped++
                continue
            }
            try {
                if (mutationGate.isStallLocked(stall.id.value)) {
                    skipped++
                } else {
                    val legacyDue = stall.ownerSince?.plus(RentTimingPolicy.collectionInterval(config))
                    val shifted = (stall.nextRentAt ?: legacyDue ?: now).plus(duration)
                    val shouldRecover = stall.state == StallState.GRACE && shifted.isAfter(now)
                    val nextState = if (shouldRecover) StallState.OWNED else stall.state
                    val changed = stall.copy(nextRentAt = shifted, state = nextState)

                    stalls.save(changed)
                    updated++
                    if (shouldRecover) {
                        recovered++
                        fireStateChanged(stall, changed)
                    }
                }
            } catch (failure: Exception) {
                failed++
                log.warning(
                    "BulkRentExtensionService: failed to extend stall ${stall.id.value}: ${failure.message}"
                )
            }
        }

        return BulkRentExtensionReport(updated, recovered, skipped, failed)
    }

    private fun fireStateChanged(previous: Stall, current: Stall) {
        if (previous.state == current.state) return
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                StallStateChangedEvent(previous.id.value, previous.state, current.state)
            )
        } catch (failure: Exception) {
            log.warning(
                "BulkRentExtensionService: failed to fire StallStateChangedEvent for " +
                    "${previous.id.value}: ${failure.message}"
            )
        }
    }
}
