package net.badgersmc.em.infrastructure.scheduler

import net.badgersmc.em.application.AuctionLifecycleService
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.vault.VaultHealth
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import java.time.Duration
import java.time.Instant
import java.util.logging.Level
import net.badgersmc.nexus.annotations.PostConstruct

/**
 * Repeatedly settles expired auctions on a fixed schedule and sends
 * time-remaining reminders to active bidders.
 *
 * Runs every 20 seconds (400 ticks) asynchronously to avoid blocking the
 * main server thread for database and economy operations.
 */
@Component
class AuctionScheduler(
    private val plugin: Plugin,
    private val auctionLifecycleService: AuctionLifecycleService,
    private val auctionRepository: AuctionRepository,
    private val vaultHealth: VaultHealth,
    private val lang: LangService,
) {

    /** Reminder thresholds: at these durations remaining, notify the high bidder. */
    private val reminderThresholds = listOf(
        Duration.ofMinutes(5),
        Duration.ofMinutes(1),
        Duration.ofSeconds(30),
    )

    /** Tracks which (auctionId, thresholdSeconds) pairs have already been reminded. */
    private val reminded: MutableSet<Pair<String, Long>> = HashSet()

    @PostConstruct
    fun start() {
        if (!vaultHealth.isAvailable) {
            plugin.logger.warning("Vault not available — auction settlement disabled")
            return
        }
        object : BukkitRunnable() {
            override fun run() {
                try {
                    val report = auctionLifecycleService.settleExpired()
                    if (report.settled > 0 || report.errors > 0) {
                        plugin.logger.info(
                            "Auction settlement: ${report.settled} settled, ${report.errors} errors"
                        )
                    }
                    sendReminders()
                } catch (e: Exception) {
                    plugin.logger.log(Level.SEVERE, "Error during auction settlement tick", e)
                }
            }
        }.runTaskTimer(plugin, 400L, 400L) // 20 seconds initial delay, 20 seconds interval
    }

    private fun sendReminders() {
        val now = Instant.now()
        for (auction in auctionRepository.allOpen()) {
            val remaining = Duration.between(now, auction.endAt)
            if (remaining.isNegative) continue
            sendReminderForAuction(auction, remaining)
        }
    }

    private fun sendReminderForAuction(auction: net.badgersmc.em.domain.auction.Auction, remaining: Duration) {
        val bidderUuid = auction.highBid?.bidder ?: return
        val bidder = Bukkit.getPlayer(bidderUuid) ?: return
        val hit = reminderThresholds.lastOrNull { remaining <= it }
            ?: return
        val thresholdKey = auction.id.value to hit.seconds
        if (reminded.add(thresholdKey)) {
            bidder.sendMessage(
                lang.msg(
                    "auction.ending_soon",
                    "stall" to auction.stallId.value,
                    "time" to formatDuration(remaining),
                    "amount" to (auction.highBid?.amount ?: auction.startingBid),
                )
            )
        }
    }

    private fun formatDuration(d: Duration): String {
        val minutes = d.toMinutes()
        val seconds = d.seconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}