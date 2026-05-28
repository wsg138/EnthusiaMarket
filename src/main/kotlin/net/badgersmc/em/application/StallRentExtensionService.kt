package net.badgersmc.em.application

import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * Pay one rent period to push the stall's `nextRentAt` forward by
 * `rent.collectionInterval`. Triggered from the purchase-sign click
 * confirmation flow on OWNED / GRACE stalls.
 *
 * Authorisation: only the SOLO owner, or a guild member with
 * `MANAGE_SHOPS` for GUILD-owned stalls (`Stall.canManage`).
 */
@Service
class StallRentExtensionService(
    private val stalls: StallRepository,
    private val economy: EconomyProvider,
    private val guildProvider: GuildProvider,
    private val config: EnthusiaMarketConfig,
) {

    private val log = Logger.getLogger(StallRentExtensionService::class.java.name)

    sealed interface Result {
        data class Extended(val newNextRentAt: Instant, val amountPaid: Long) : Result
        data object NotFound : Result
        data object NotAuthorised : Result
        data object NotOwned : Result
        data class Rejected(val reason: String) : Result
    }

    fun extend(stallId: StallId, actor: UUID): Result {
        val stall = stalls.findById(stallId) ?: return Result.NotFound

        if (stall.state != StallState.OWNED && stall.state != StallState.GRACE) {
            return Result.NotOwned
        }
        if (!stall.canManage(actor, guildProvider)) return Result.NotAuthorised

        val amount = stall.rentTerms.dailyRent(stall.winningBid)
        if (amount <= 0) {
            // Rent-free stall — no-op extension still bumps the timer
            // so the sign updates immediately.
            val pushed = pushTimer(stall.nextRentAt)
            val updated = stall.copy(nextRentAt = pushed, state = StallState.OWNED)
            stalls.save(updated)
            fireStateChanged(stallId.value, stall.state, updated.state)
            return Result.Extended(pushed, 0L)
        }

        if (!economy.withdraw(actor, amount)) {
            return Result.Rejected("Insufficient funds: $amount required")
        }

        val pushed = pushTimer(stall.nextRentAt)
        try {
            val updated = stall.copy(nextRentAt = pushed, state = StallState.OWNED)
            stalls.save(updated)
            fireStateChanged(stallId.value, stall.state, updated.state)
        } catch (e: Exception) {
            log.severe(
                "StallRentExtensionService.extend: persist failed for ${stallId.value} " +
                    "after charging $actor amount=$amount. Manual refund required. cause=${e.message}"
            )
            throw e
        }
        return Result.Extended(pushed, amount)
    }

    private fun pushTimer(current: Instant?): Instant {
        val interval = collectionInterval()
        val now = Instant.now()
        // Push from whichever is later — the existing due date (so
        // pre-paying truly extends) or now (so a long-overdue stall
        // doesn't get back-dated credit).
        val base = current?.takeIf { it.isAfter(now) } ?: now
        return base.plus(interval)
    }

    private fun collectionInterval(): Duration = try {
        Duration.parse(config.rent.collectionInterval)
    } catch (_: java.time.format.DateTimeParseException) {
        Duration.ofDays(1)
    }

    private fun fireStateChanged(stallId: String, previous: StallState, current: StallState) {
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                StallStateChangedEvent(stallId, previous, current)
            )
        } catch (e: Exception) {
            log.warning("Failed to fire StallStateChangedEvent for $stallId: ${e.message}")
        }
    }
}
