package net.badgersmc.em.infrastructure.scheduler

import net.badgersmc.em.application.RentCollectionService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import java.time.Duration
import java.util.logging.Level

/**
 * Repeatedly collects rent from stall owners on a fixed schedule.
 *
 * The interval is read from config ([EnthusiaMarketConfig.Rent.collectionInterval])
 * as an ISO-8601 duration and converted to server ticks (20 ticks/second).
 *
 * First tick fires immediately (0 delay) so overdue stalls are processed on boot
 * instead of waiting for the full interval. This also means a server restart won't
 * reset the timer — overdue stalls are caught on boot.
 */
@Component
class RentScheduler(
    private val plugin: Plugin,
    private val rentCollectionService: RentCollectionService,
    private val config: EnthusiaMarketConfig
) {

    @PostConstruct
    @Suppress("ComplexCondition")
    fun start() {
        val intervalTicks = parseInterval()
        object : BukkitRunnable() {
            override fun run() {
                try {
                    val report = rentCollectionService.tick()
                    if (report.collected > 0 || report.defaults > 0 ||
                        report.evictions > 0 || report.errors > 0
                    ) {
                        plugin.logger.info(
                            "Rent collection: ${report.collected} collected, " +
                            "${report.defaults} defaulted, ${report.evictions} evicted, " +
                            "${report.errors} errors"
                        )
                    }
                } catch (e: Exception) {
                    plugin.logger.log(Level.SEVERE, "Error during rent collection tick", e)
                }
            }
        }.runTaskTimer(plugin, 0L, intervalTicks)  // 0 delay = immediate first run
    }

    private fun parseInterval(): Long {
        val duration = runCatching {
            val d = Duration.parse(config.rent.collectionInterval)
            if (d.isZero || d.isNegative) Duration.ofDays(1) else d
        }.getOrElse { Duration.ofDays(1) }
        // Convert ms to ticks (50ms/tick), enforce minimum 1 tick.
        // Using ms preserves fractional-second precision that .seconds would truncate.
        return (duration.toMillis() / 50).coerceAtLeast(1)
    }
}