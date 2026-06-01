package net.badgersmc.em.infrastructure.scheduler

import net.badgersmc.em.application.RentCollectionService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.nexus.vault.VaultHealth
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
 */
@Component
class RentScheduler(
    private val plugin: Plugin,
    private val rentCollectionService: RentCollectionService,
    private val vaultHealth: VaultHealth,
    private val config: EnthusiaMarketConfig
) {

    @PostConstruct
    @Suppress("ComplexCondition")
    fun start() {
        if (!vaultHealth.isAvailable) {
            plugin.logger.warning("Vault not available — rent collection disabled")
            return
        }
        val intervalTicks = Duration.parse(config.rent.collectionInterval).let { duration ->
            (duration.seconds * 20).toLong()
        }
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
        }.runTaskTimer(plugin, intervalTicks, intervalTicks)
    }
}