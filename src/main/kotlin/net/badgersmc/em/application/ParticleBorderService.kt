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