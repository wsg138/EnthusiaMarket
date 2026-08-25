package net.badgersmc.em.application

import net.badgersmc.em.domain.maintenance.FreezeShiftResult
import net.badgersmc.em.domain.maintenance.MaintenanceFreezeRepository
import net.badgersmc.nexus.annotations.Service
import java.time.Duration
import java.time.Instant
import java.util.logging.Logger

/**
 * Outcome of a maintenance-freeze command.
 */
sealed class MaintenanceFreezeResult {
    /** A freeze was started at [since]. */
    data class Activated(val since: Instant) : MaintenanceFreezeResult()
    /** A freeze was already active when `begin` was called. */
    data class AlreadyActive(val since: Instant) : MaintenanceFreezeResult()
    /** The freeze was lifted; [stalls] rent deadlines and [auctions] end times
     *  were shifted forward by [elapsed]. */
    data class Lifted(val stalls: Int, val auctions: Int, val elapsed: Duration) : MaintenanceFreezeResult()
    /** `end` was called with no freeze active. */
    data object NotFrozen : MaintenanceFreezeResult()
}

/** Current maintenance-freeze state. */
data class MaintenanceFreezeStatus(val frozen: Boolean, val since: Instant?)

/**
 * Application-layer service for the maintenance freeze: a global pause on all
 * timer-driven market processing (rent grace/eviction, auction settlement and
 * reminders) for a server-maintenance window.
 *
 * While frozen, the rent and auction schedulers skip their ticks. On unfreeze,
 * every timer column is shifted forward by the frozen wall-clock duration, so
 * players lose no time and no one is evicted / no auction expires while the
 * server was closed.
 *
 * The state is DB-backed and survives restarts — the server is typically taken
 * DOWN for the maintenance window, so the freeze must still be active when it
 * comes back up until an admin runs `/em maintenance unfreeze`.
 */
@Service
class MaintenanceFreezeService(
    private val repo: MaintenanceFreezeRepository,
) {
    private val log = Logger.getLogger(MaintenanceFreezeService::class.java.name)

    /** Cached DB state so the schedulers' per-tick check is a volatile read. */
    @Volatile
    private var frozenSinceCache: Instant? = null
    @Volatile
    private var loaded = false
    private val cacheLock = Any()

    /** True while a maintenance freeze is active. Cheap — safe for per-tick calls. */
    fun isFrozen(): Boolean = frozenSince() != null

    /**
     * The instant the active freeze began, or null when not frozen.
     * Loads from the DB once (lazily) and caches; [begin]/[end] refresh it.
     */
    fun frozenSince(): Instant? {
        if (loaded) return frozenSinceCache
        synchronized(cacheLock) {
            if (!loaded) {
                frozenSinceCache = repo.frozenSince()
                loaded = true
                if (frozenSinceCache != null) {
                    log.warning(
                        "MAINTENANCE FREEZE IS ACTIVE since $frozenSinceCache — " +
                            "rent and auction timers are paused. Run /em maintenance unfreeze " +
                            "when the server is back."
                    )
                }
            }
            return frozenSinceCache
        }
    }

    /** Start a maintenance freeze. Returns the freeze state. */
    fun begin(now: Instant = Instant.now()): MaintenanceFreezeResult {
        val existing = frozenSince()
        if (existing != null) return MaintenanceFreezeResult.AlreadyActive(existing)
        repo.begin(now)
        synchronized(cacheLock) {
            frozenSinceCache = now
            loaded = true
        }
        log.info("Maintenance freeze ACTIVATED — rent and auction timers paused")
        return MaintenanceFreezeResult.Activated(now)
    }

    /**
     * Lift the freeze: shift every timer forward by the elapsed frozen time and
     * clear the flag. Returns [MaintenanceFreezeResult.NotFrozen] when no
     * freeze is active.
     */
    fun end(now: Instant = Instant.now()): MaintenanceFreezeResult {
        val since = frozenSince()
        if (since == null) return MaintenanceFreezeResult.NotFrozen
        val elapsed = Duration.between(since, now).coerceAtLeast(Duration.ZERO)
        val report = repo.unfreeze(elapsed.toMillis())
        synchronized(cacheLock) {
            frozenSinceCache = null
            loaded = true
        }
        log.info(
            "Maintenance freeze LIFTED — shifted ${report.stalls} stall rent deadline(s) " +
                "and ${report.auctions} auction(s) forward by ${elapsed.toMillis()}ms"
        )
        return MaintenanceFreezeResult.Lifted(report.stalls, report.auctions, elapsed)
    }

    /** Current freeze state for status display. */
    fun status(): MaintenanceFreezeStatus {
        val since = frozenSince()
        return MaintenanceFreezeStatus(frozen = since != null, since = since)
    }
}
