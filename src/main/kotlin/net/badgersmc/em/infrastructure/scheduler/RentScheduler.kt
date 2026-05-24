package net.badgersmc.em.infrastructure.scheduler

import net.badgersmc.em.application.RentCollectionService
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.logging.Level

/**
 * Repeatedly collects rent from stall owners on a fixed schedule.
 *
 * Runs every 60 seconds (1200 ticks) asynchronously to avoid blocking the
 * main server thread for database and economy operations.
 * In production, the tick interval should be tuned via config.
 */
@Component
class RentScheduler(
    private val plugin: Plugin,
    private val rentCollectionService: RentCollectionService
) {

    @PostConstruct
    fun start() {
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
        }.runTaskTimer(plugin, 1200L, 1200L) // 60 seconds initial delay, 60 seconds interval
    }
}