package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState

class ImportStallsService(
    private val regions: RegionProvider,
    private val stalls: StallRepository,
    private val defaultRent: RentTerms
) {
    data class Result(val created: Int, val skipped: Int)

    fun import(world: String, prefix: String): Result {
        var created = 0
        var skipped = 0
        for (ref in regions.listByPrefix(world, prefix)) {
            if (stalls.findByRegion(ref.world, ref.id) != null) {
                skipped++
                continue
            }
            stalls.create(
                Stall(
                    id = StallId(ref.id),
                    regionId = ref.id,
                    world = ref.world,
                    state = StallState.UNOWNED,
                    owner = OwnerRef.unowned(),
                    ownerSince = null,
                    winningBid = 0L,
                    rentTerms = defaultRent
                )
            )
            created++
        }
        return Result(created, skipped)
    }
}
