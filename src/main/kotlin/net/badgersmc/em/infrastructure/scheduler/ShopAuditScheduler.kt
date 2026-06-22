package net.badgersmc.em.infrastructure.scheduler

import net.badgersmc.em.application.ShopAuditDecision
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
        var processed = 0
        // Cap at the list size so a tick never re-audits the same shop when
        // there are fewer shops than maxPerTick. The cursor still advances
        // across ticks, so over time every shop is swept.
        val max = minOf(config.shopAudit.maxPerTick, all.size)
        while (processed < max) {
            if (cursor >= all.size) cursor = 0
            val shop = all[cursor]
            cursor++
            processed++
            auditOne(shop)
        }
    }

    private fun auditOne(shop: net.badgersmc.em.domain.shop.Shop) {
        // Gate on the container's CHUNK being loaded, not just the world: Paper returns AIR for
        // blocks in unloaded chunks, so an unloaded chunk must read as unobservable → SKIP, never
        // REMOVE. Mirrors EnthusiaMarket.loadedContainer; `shr 4` maps a block coord to its chunk.
        val world = Bukkit.getWorld(shop.containerWorld)
        val chunkLoaded = world != null &&
            world.isChunkLoaded(shop.containerX shr 4, shop.containerZ shr 4)
        val isContainer = chunkLoaded && world!!.getBlockAt(
            shop.containerX, shop.containerY, shop.containerZ
        ).state is Container
        if (ShopAuditDecision.evaluate(chunkLoaded, isContainer) == ShopAuditDecision.Decision.REMOVE &&
            config.shopAudit.repairEnabled
        ) {
            removeOrphan(shop)
        }
    }

    private fun removeOrphan(shop: net.badgersmc.em.domain.shop.Shop) {
        shops.delete(shop.id)
        plugin.logger.info(
            "[audit] removed orphaned shop ${shop.id} at " +
            "${shop.containerWorld} ${shop.containerX},${shop.containerY},${shop.containerZ}"
        )
    }
}
