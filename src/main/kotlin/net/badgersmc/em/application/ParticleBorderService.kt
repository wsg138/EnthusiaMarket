package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.RegionProvider.RegionBounds
import net.badgersmc.nexus.annotations.Component

/**
 * Plans + tracks player stall outlines (REQ-240/241). The particle budget
 * is split as a single global spacing: total perimeter across all active
 * outlines is sampled at a spacing chosen so the summed point count never
 * exceeds maxPerTick. Degrades by widening spacing, never by dropping an
 * outline. Pure planning is unit-tested; Bukkit spawn is a thin wrapper.
 */
@Component
class ParticleBorderService {

    /** A planned outline: the world-space points to spawn a particle at. */
    data class OutlinePlan(val points: List<Triple<Double, Double, Double>>)

    private data class ActiveOutline(
        val player: java.util.UUID,
        val stallId: String,
        val world: String,
        val bounds: RegionBounds,
        val expiresAt: java.time.Instant,
    )

    private val active = java.util.concurrent.ConcurrentHashMap<Pair<java.util.UUID, String>, ActiveOutline>()

    fun addOutline(
        player: java.util.UUID,
        stallId: String,
        world: String,
        bounds: RegionBounds,
        expiresAt: java.time.Instant,
    ) {
        active[player to stallId] = ActiveOutline(player, stallId, world, bounds, expiresAt)
    }

    fun activeCount(): Int = active.size

    fun purgeExpired(now: java.time.Instant) {
        active.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }

    /** Bounds of all active outlines, for the per-tick planner. */
    fun activeBounds(): List<RegionBounds> =
        active.values.map { it.bounds }

    /** Spawn particles for the current tick within [maxPerTick]; END_ROD, per-player. */
    fun renderTick(maxPerTick: Int, plugin: org.bukkit.plugin.Plugin) {
        if (active.isEmpty()) return
        val entries = active.values.toList()
        val plans = planParticles(entries.map { it.bounds }, maxPerTick)
        for ((idx, outline) in entries.withIndex()) {
            val player = org.bukkit.Bukkit.getPlayer(outline.player) ?: continue
            // spawnParticle is player-relative — only render when the player is
            // actually in the outlined stall's world, else the border would draw
            // at the stall's coords in whatever world the player walked into.
            if (player.world.name != outline.world) continue
            for ((x, y, z) in plans[idx].points) {
                player.spawnParticle(org.bukkit.Particle.END_ROD, x + 0.5, y + 0.5, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    companion object {
        /**
         * Plan particle points for every outline so total points <= [maxPerTick].
         * Spacing is uniform across all outlines (global density knob).
         */
        fun planParticles(outlines: List<RegionBounds>, maxPerTick: Int): List<OutlinePlan> {
            if (outlines.isEmpty() || maxPerTick <= 0) {
                return outlines.map { OutlinePlan(emptyList()) }.takeIf { outlines.isNotEmpty() && maxPerTick <= 0 }
                    ?: emptyList()
            }
            val totalPerimeter = outlines.sumOf { perimeter(it) }
            if (totalPerimeter <= 0.0) return outlines.map { OutlinePlan(emptyList()) }
            // spacing so that totalPerimeter / spacing <= maxPerTick.
            val spacing = (totalPerimeter / (maxPerTick * 0.8)).coerceAtLeast(1.0)
            return outlines.map { OutlinePlan(edgePoints(it, spacing)) }
        }

        /** Sum of the 12 edge lengths of the cuboid (block spans). */
        private fun perimeter(b: RegionBounds): Double {
            val w = b.width.toDouble()
            val h = b.height.toDouble()
            val l = b.length.toDouble()
            return 4.0 * (w + h + l)
        }

        /** Sample points along the 12 cuboid edges at [spacing] intervals. */
        private fun edgePoints(b: RegionBounds, spacing: Double): List<Triple<Double, Double, Double>> {
            val pts = mutableListOf<Triple<Double, Double, Double>>()
            val xs = listOf(b.minX.toDouble(), b.maxX.toDouble())
            val ys = listOf(b.minY.toDouble(), b.maxY.toDouble())
            val zs = listOf(b.minZ.toDouble(), b.maxZ.toDouble())
            // Edges along X (vary x, fixed y,z corners)
            for (y in ys) for (z in zs) sampleLine(b.minX.toDouble(), b.maxX.toDouble(), spacing) { x -> pts.add(Triple(x, y, z)) }
            // Edges along Y
            for (x in xs) for (z in zs) sampleLine(b.minY.toDouble(), b.maxY.toDouble(), spacing) { y -> pts.add(Triple(x, y, z)) }
            // Edges along Z
            for (x in xs) for (y in ys) sampleLine(b.minZ.toDouble(), b.maxZ.toDouble(), spacing) { z -> pts.add(Triple(x, y, z)) }
            return pts
        }

        private inline fun sampleLine(from: Double, to: Double, spacing: Double, emit: (Double) -> Unit) {
            var p = from
            while (p <= to) {
                emit(p)
                p += spacing
            }
        }
    }
}