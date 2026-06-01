package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.EntityLimitConfig
import net.badgersmc.em.application.StallEntityCounter
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.stall.EntityLimitGroup
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import java.io.File

/**
 * Enforces per-stall entity caps (REQ-221). Player-attributable creature
 * spawns and all entity/hanging placements are checked against the stall's
 * kind group (merged with per-stall extras). Natural spawns are ignored.
 * See spec §3 Workstream B.
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
class EntityLimitListener(
    private val regions: RegionProvider,
    private val stalls: StallRepository,
    private val config: EnthusiaMarketConfig,
    private val counter: StallEntityCounter,
    private val plugin: org.bukkit.plugin.Plugin,
) : Listener {

    // Loaded once on construction; reload via /em reload re-creates beans.
    private val groups: Map<String, EntityLimitGroup> =
        EntityLimitConfig.load(File(plugin.dataFolder, "entitylimits.yml"))

    companion object {
        /** Spawn reasons attributable to players (REQ-221) — natural spawns excluded. */
        private val PLAYER_REASONS = setOf(
            CreatureSpawnEvent.SpawnReason.BREEDING,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.EGG,
            CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,
            CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM,
            CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN,
            CreatureSpawnEvent.SpawnReason.BUILD_WITHER,
        )

        /**
         * Pure decision: should this spawn/place be cancelled? Increments the
         * counter on accept. Exposed for unit testing without Bukkit.
         */
        fun decide(
            stallId: String,
            type: String,
            group: EntityLimitGroup,
            counter: StallEntityCounter,
            rescan: (String) -> Map<String, Int>,
        ): Boolean {
            if (counter.wouldExceedTypeCap(stallId, type, group.capFor(type), rescan)) return true
            if (counter.wouldExceedTotal(stallId, group.total, rescan)) return true
            counter.increment(stallId, type)
            return false
        }
    }

    @EventHandler
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (event.spawnReason !in PLAYER_REASONS) return
        if (checkAndMaybeCancel(event.location, event.entityType.name)) event.isCancelled = true
    }

    @EventHandler
    fun onEntityPlace(event: EntityPlaceEvent) {
        if (checkAndMaybeCancel(event.entity.location, event.entity.type.name)) event.isCancelled = true
    }

    @EventHandler
    fun onHangingPlace(event: HangingPlaceEvent) {
        if (checkAndMaybeCancel(event.entity.location, event.entity.type.name)) event.isCancelled = true
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val stallId = resolveStall(event.entity.location) ?: return
        counter.decrement(stallId, event.entityType.name.lowercase(java.util.Locale.ROOT))
    }

    private fun checkAndMaybeCancel(loc: Location, entityTypeName: String): Boolean {
        val stallId = resolveStall(loc) ?: return false
        val stall = stalls.findById(StallId(stallId)) ?: return false
        val baseGroup = EntityLimitConfig.groupFor(groups, stall.kind)
        val group = baseGroup.mergeExtras(stall.extraTotal, stall.extraEntities)
        val type = entityTypeName.lowercase(java.util.Locale.ROOT)
        return decide(stallId, type, group, counter, ::scanCounts)
    }

    private fun resolveStall(loc: Location): String? {
        val world = loc.world?.name ?: return null
        val id = regions.regionAt(world, loc.blockX, loc.blockY, loc.blockZ) ?: return null
        return if (id.startsWith(config.market.regionPrefix)) id else null
    }

    /**
     * Authoritative live scan of capped entity counts within the stall region.
     * Bounded to the region's cuboid via [org.bukkit.World.getNearbyEntities]
     * (REQ-221) — scanning the whole world per boundary hit would spike a tick
     * on a busy server. Stall regions are cuboids, so the bounding box equals
     * the region; no per-entity region re-resolution needed.
     */
    private fun scanCounts(stallId: String): Map<String, Int> {
        val stall = stalls.findById(StallId(stallId)) ?: return emptyMap()
        val world = org.bukkit.Bukkit.getWorld(stall.world) ?: return emptyMap()
        val b = regions.bounds(stall.world, stall.regionId) ?: return emptyMap()
        val box = org.bukkit.util.BoundingBox(
            b.minX.toDouble(), b.minY.toDouble(), b.minZ.toDouble(),
            (b.maxX + 1).toDouble(), (b.maxY + 1).toDouble(), (b.maxZ + 1).toDouble(),
        )
        val counts = HashMap<String, Int>()
        for (entity: Entity in world.getNearbyEntities(box)) {
            val t = entity.type.name.lowercase(java.util.Locale.ROOT)
            counts[t] = (counts[t] ?: 0) + 1
        }
        return counts
    }
}