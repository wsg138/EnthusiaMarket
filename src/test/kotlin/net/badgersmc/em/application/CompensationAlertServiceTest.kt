package net.badgersmc.em.application

import java.util.UUID
import kotlin.test.Test

/**
 * TDD-2 / REQ-1 — CompensationAlertService must be safe to call when no
 * Bukkit server is running (unit test context, very-early bootstrap).
 *
 * alert() must:
 *   - write the SEVERE log line
 *   - not throw on the null-safe Bukkit.getServer()?.pluginManager path
 */
class CompensationAlertServiceTest {

    @Test
    fun `alert does not throw when Bukkit server is unavailable`() {
        val service = CompensationAlertService()
        val affected = UUID.fromString("00000000-0000-0000-0000-000000000042")

        // In a unit test context, Bukkit.getServer() is null. The service
        // must short-circuit cleanly via the ?. chain rather than NPE.
        service.alert(
            context = "sell-offer.purchase.refund",
            detail = "buyer charged but ownership transfer failed",
            affected = affected,
            amount = 1500L,
        )

        // null affected UUID is also a valid path (e.g. tax destination
        // could not be resolved and no player is implicated).
        service.alert(
            context = "sell-offer.purchase.tax-routing",
            detail = "tax destination missing or invalid",
            affected = null,
            amount = 75L,
        )
    }
}
