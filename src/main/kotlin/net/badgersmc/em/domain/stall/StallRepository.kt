package net.badgersmc.em.domain.stall

interface StallRepository {
    fun findById(id: StallId): Stall?
    fun findByIds(ids: Collection<StallId>): Map<StallId, Stall> =
        ids.mapNotNull { id -> findById(id)?.let { id to it } }.toMap()
    fun findByRegion(world: String, regionId: String): Stall?
    fun all(): List<Stall>
    fun byState(state: StallState): List<Stall>
    fun save(stall: Stall)
    fun create(stall: Stall)
}
