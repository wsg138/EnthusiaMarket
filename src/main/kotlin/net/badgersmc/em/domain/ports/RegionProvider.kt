package net.badgersmc.em.domain.ports

interface RegionProvider {
    data class RegionRef(val world: String, val id: String)

    /** All region IDs in [world] whose id starts with [prefix]. */
    fun listByPrefix(world: String, prefix: String): List<RegionRef>

    /** True if a region with [id] exists in [world]. */
    fun exists(world: String, id: String): Boolean
}
