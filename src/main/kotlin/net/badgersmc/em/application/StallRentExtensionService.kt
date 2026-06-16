package net.badgersmc.em.application

import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.OwnerType
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

        // Pre-payment cap (REQ-286): stop players from extending arbitrarily far ahead. When the
        // stall's nextRentAt is already >= now + maxPrepaidPeriods*interval, reject before charging.
        // <= 0 means unlimited (legacy). Guards against the "infinite extend" exploit.
        val cap = config.rent.maxPrepaidPeriods
        if (cap > 0) {
            val current = stall.nextRentAt
            val ceiling = Instant.now().plus(collectionInterval().multipliedBy(cap.toLong()))
            if (current != null && !current.isBefore(ceiling)) {
                return Result.Rejected("Rent is already prepaid the maximum of $cap period(s) ahead")
            }
        }

        // Floor to at least 1 unit whenever the stall had a real buy
        // price. Default rent.formulaPct (0.01) on a 1000-coin stall
        // computes to 0.0001 → toLong() = 0, which previously let
        // players infinite-extend for free. The floor closes that hole
        // until operators tune formulaPct (typically 1.0 = 1% per
        // period). True rent-free stalls (winningBid <= 0) keep the
        // no-charge path so admin-gifted regions don't surprise-bill.
        val computed = stall.rentTerms.dailyRent(stall.winningBid)
        val amount = if (stall.winningBid > 0L) maxOf(computed, 1L) else computed
        if (amount <= 0) {
            // Truly rent-free (winningBid == 0). Bump the timer for
            // sign-display purposes; no economy involvement.
            val pushed = pushTimer(stall.nextRentAt)
            val updated = stall.copy(nextRentAt = pushed, state = StallState.OWNED)
            stalls.save(updated)
            fireStateChanged(stallId.value, stall.state, updated.state)
            return Result.Extended(pushed, 0L)
        }

        val isGuild = stall.owner.type == OwnerType.GUILD
        val charged = if (isGuild) guildProvider.bankWithdraw(stall.owner.id, amount)
                      else economy.withdraw(actor, amount)
        if (!charged) {
            return Result.Rejected(
                if (isGuild) "The guild bank has insufficient funds: $amount required"
                else "Insufficient funds: $amount required"
            )
        }

        val pushed = pushTimer(stall.nextRentAt)
        try {
            val updated = stall.copy(nextRentAt = pushed, state = StallState.OWNED)
            stalls.save(updated)
            fireStateChanged(stallId.value, stall.state, updated.state)
        } catch (e: Exception) {
            // Refund the actor before re-throwing so a persistence failure doesn't leave them
            // charged for an extension that never applied. economy.deposit returns false on
            // failure (and may also throw), so check both; either way the ORIGINAL persistence
            // exception (root cause) is rethrown.
            val refunded = try {
                if (isGuild) guildProvider.bankDeposit(stall.owner.id, amount)
                else economy.deposit(actor, amount)
            } catch (refund: Exception) {
                log.severe("StallRentExtensionService.extend: refund of $amount (guild=$isGuild) threw: ${refund.message}")
                false
            }
            if (refunded) {
                log.severe(
                    "StallRentExtensionService.extend: persist failed for ${stallId.value} after charging " +
                        "$actor amount=$amount. Actor has been refunded. cause=${e.message}"
                )
            } else {
                log.severe(
                    "StallRentExtensionService.extend: persist failed for ${stallId.value} AND the refund of " +
                        "$amount to $actor failed — manual refund required. cause=${e.message}"
                )
            }
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
