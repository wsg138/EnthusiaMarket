package net.badgersmc.em.application

import net.badgersmc.em.events.TradeCompensationFailedEvent
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import java.util.UUID
import java.util.logging.Logger

/**
 * Single funnel for "the trade pipeline could not auto-reconcile" signals.
 *
 * Each call:
 *   1. writes a SEVERE log line (operator-facing, no event listener needed)
 *   2. fires [TradeCompensationFailedEvent] for any in-game listeners
 *
 * Null-safe against an absent [Bukkit.getServer] (unit tests, very-early
 * bootstrap) and against a listener that throws while handling the event.
 * Either failure mode is downgraded to a WARNING — the alert contract is
 * "never throw out of a compensation failure".
 */
@Service
class CompensationAlertService {
    private val log = Logger.getLogger(CompensationAlertService::class.java.name)

    fun alert(context: String, detail: String, affected: UUID?, amount: Long) {
        log.severe("COMPENSATION FAILED [$context]: $detail (affected=$affected, amount=$amount) — manual reconciliation required.")
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                TradeCompensationFailedEvent(context, detail, affected, amount)
            )
        } catch (e: Exception) {
            log.warning("CompensationAlertService: failed to fire TradeCompensationFailedEvent for [$context]: ${e.message}")
        }
    }
}
