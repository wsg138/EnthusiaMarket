package net.badgersmc.em.infrastructure.scheduler

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.logging.Level

/**
 * Periodically audits all shops and removes orphaned ones whose container block
 * is gone (IS2-8, REQ-294). Throttled via [EnthusiaMarketConfig.ShopAudit.maxPerTick]
 * so large shop counts don't stall the main thread.
 */
@Component
class ShopAuditScheduler(
    private val plugin: Plugin,
    private val shops: ShopRepository,
    private val config: EnthusiaMarketConfig
) {
    private var cursor: Int = 0

    @PostConstruct
    fun start() {
        if (!config.shopAudit.enabled) {
            plugin.logger.info("Shop audit disabled in config — skipping")
            return
        }
        val intervalTicks = config.shopAudit.intervalMinutes.toLong() * 60L * 20L
        object : BukkitRunnable() {
            override fun run() {
                try {
                    tick()
                } catch (e: Exception) {
                    plugin.logger.log(Level.SEVERE, "Error during shop audit tick", e)
                }
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks)
    }

    private fun tick() {
        val all = shops.all()
        if (all.isEmpty()) return
        // Cap at the list size so a tick never re-audits the same shop when
        // there are fewer shops than maxPerTick. The cursor still advances
        // across ticks, so over time every shop is swept.
        val max = minOf(config.shopAudit.maxPerTick, all.size)
        report(auditBatch(all, max))
    }

    private fun auditBatch(
        all: List<net.badgersmc.em.domain.shop.Shop>,
        maximum: Int,
    ): AuditSummary {
        val results = buildList {
            repeat(maximum) {
                if (cursor >= all.size) cursor = 0
                val shop = all[cursor++]
                add(shop to auditOne(shop))
            }
        }
        return AuditSummary(
            processed = results.size,
            removed = results.count { it.second == Decision.REMOVED },
            skipped = results.filter { it.second == Decision.SKIPPED }.map { it.first.id.toString() },
        )
    }

    private fun report(summary: AuditSummary) {
        if (summary.removed == 0 && summary.skipped.isEmpty()) return
        val skipped = summary.skipped.takeIf { it.isNotEmpty() }?.let {
            ", ${it.size} skipped (unloaded chunks: ${it.take(3).joinToString()})"
        }.orEmpty()
        plugin.logger.info(
            "[audit] sweep: ${summary.processed} checked, ${summary.removed} removed$skipped"
        )
    }

    private enum class Decision { KEPT, REMOVED, SKIPPED }

    private data class AuditSummary(
        val processed: Int,
        val removed: Int,
        val skipped: List<String>,
    )

    private fun auditOne(shop: net.badgersmc.em.domain.shop.Shop): Decision {
        if (containerIsMissing(shop) && config.shopAudit.repairEnabled) {
            removeOrphan(shop)
            return Decision.REMOVED
        }
        val world = Bukkit.getWorld(shop.containerWorld)
        val chunkLoaded = world != null &&
            world.isChunkLoaded(shop.containerX shr 4, shop.containerZ shr 4)
        return if (chunkLoaded) Decision.KEPT else Decision.SKIPPED
    }

    /** Returns true when the container block is observable and is NOT a Container. */
    private fun containerIsMissing(shop: net.badgersmc.em.domain.shop.Shop): Boolean {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return false
        if (!world.isChunkLoaded(shop.containerX shr 4, shop.containerZ shr 4)) return false
        val block = world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ)
        return block.state !is Container
    }

    private fun removeOrphan(shop: net.badgersmc.em.domain.shop.Shop) {
        shops.delete(shop.id)
        plugin.logger.info(
            "[audit] removed orphaned shop ${shop.id} at " +
            "${shop.containerWorld} ${shop.containerX},${shop.containerY},${shop.containerZ}"
        )
    }
}
