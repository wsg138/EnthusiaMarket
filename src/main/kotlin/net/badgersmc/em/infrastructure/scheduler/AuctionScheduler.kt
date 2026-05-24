package net.badgersmc.em.infrastructure.scheduler

import net.badgersmc.em.application.AuctionLifecycleService
import net.badgersmc.nexus.annotations.Component
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.logging.Level
import net.badgersmc.nexus.annotations.PostConstruct

/**
 * Repeatedly settles expired auctions on a fixed schedule.
 *
 * Runs every 20 seconds (400 ticks) asynchronously to avoid blocking the
 * main server thread for database and economy operations.
 */
@Component
class AuctionScheduler(
    private val plugin: Plugin,
    private val auctionLifecycleService: AuctionLifecycleService
) {

    @PostConstruct
    fun start() {
        object : BukkitRunnable() {
            override fun run() {
                try {
                    val report = auctionLifecycleService.settleExpired()
                    if (report.settled > 0 || report.errors > 0) {
                        plugin.logger.info(
                            "Auction settlement: ${report.settled} settled, ${report.errors} errors"
                        )
                    }
                } catch (e: Exception) {
                    plugin.logger.log(Level.SEVERE, "Error during auction settlement tick", e)
                }
            }
        }.runTaskTimer(plugin, 400L, 400L) // 20 seconds initial delay, 20 seconds interval
    }
}