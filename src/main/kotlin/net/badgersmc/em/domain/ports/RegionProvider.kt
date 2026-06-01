package net.badgersmc.em.domain.ports

interface RegionProvider {
    data class RegionRef(val world: String, val id: String)

    /** All region IDs in [world] whose id starts with [prefix]. */
    fun listByPrefix(world: String, prefix: String): List<RegionRef>

    /** True if a region with [id] exists in [world]. */
    fun exists(world: String, id: String): Boolean

    /**
     * Highest-priority region id containing the block at ([x],[y],[z]) in
     * [world] whose id is a stall, or null when no region covers the point.
     * Implementations may filter to the configured stall prefix.
     */
    fun regionAt(world: String, x: Int, y: Int, z: Int): String?

    /**
     * Bounding box of the region [id] in [world], or null if the region
     * or world is not found. The box is axis-aligned from minimum to
     * maximum block coordinates (inclusive).
     */
    data class RegionBounds(
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int,
    ) {
        /** Inclusive block span on the X axis. */
        val width: Int get() = maxX - minX + 1
        /** Inclusive block span on the Y axis. */
        val height: Int get() = maxY - minY + 1
        /** Inclusive block span on the Z axis. */
        val length: Int get() = maxZ - minZ + 1
    }

    /** Region bounding box for [id] in [world], or null if not found. */
    fun bounds(world: String, id: String): RegionBounds?
}
