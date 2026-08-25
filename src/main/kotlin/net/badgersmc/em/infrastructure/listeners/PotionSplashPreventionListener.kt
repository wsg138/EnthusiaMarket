package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.entity.LingeringPotionSplashEvent
import org.bukkit.event.entity.PotionSplashEvent

/**
 * Enforces REQ-305: potion effects must never reach entities inside market
 * regions (critical exploit fix, 2026-08-02).
 *
 * MC 1.21 applies splash/cloud potion effects additively, so repeated
 * splashes (or a lingering cloud re-applying each tick) inside a stall
 * stack effect durations without bound (observed: Resistance IV with a
 * ~52-day duration). The WorldGuard `POTION_SPLASH: DENY` flag in
 * `WorldGuardRegionProvisioner.applyFlags()` is insufficient:
 *
 *  - it is stamped only at provision/resync time — existing production
 *    regions never received it;
 *  - even when present, WG's flag cancels only the thrown
 *    `PotionSplashEvent` — it does not gate lingering clouds
 *    (`AreaEffectCloudApplyEvent`), clouds drifting in from outside, or
 *    tipped arrows (`EntityPotionEffectEvent` cause ARROW).
 *
 * This listener is the runtime invariant for EVERY stall region regardless
 * of flag state: any splash/cloud/arrow-delivered potion effect targeting
 * an entity inside a stall-prefixed region is cancelled.
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
class PotionSplashPreventionListener(
    private val regions: RegionProvider,
    private val config: EnthusiaMarketConfig,
) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPotionSplash(event: PotionSplashEvent) {
        if (isInMarketRegion(event.entity.location)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLingeringSplash(event: LingeringPotionSplashEvent) {
        if (isInMarketRegion(event.entity.location)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCloudApply(event: AreaEffectCloudApplyEvent) {
        if (isInMarketRegion(event.entity.location)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityPotionEffect(event: EntityPotionEffectEvent) {
        // Only new applications matter — never block CLEARED/REMOVED/EXPIRATION.
        if (event.action != EntityPotionEffectEvent.Action.ADDED &&
            event.action != EntityPotionEffectEvent.Action.CHANGED
        ) {
            return
        }
        if (event.cause !in POTION_CAUSES) return
        if (isInMarketRegion(event.entity.location)) {
            event.isCancelled = true
        }
    }

    private fun isInMarketRegion(location: Location): Boolean {
        val world = location.world ?: return false
        val id = regions.regionAt(world.name, location.blockX, location.blockY, location.blockZ)
            ?: return false
        return id.startsWith(config.market.regionPrefix)
    }

    companion object {
        /** Potion-delivery causes that stack additively in MC 1.21 (REQ-305). */
        private val POTION_CAUSES = setOf(
            EntityPotionEffectEvent.Cause.POTION_SPLASH,
            EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD,
            EntityPotionEffectEvent.Cause.ARROW,
        )
    }
}
