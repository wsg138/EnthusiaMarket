package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.annotations.Service

/**
 * Builds the structured [StallInfo] card for a stall (REQ-230/231). Pulls
 * geometry from [RegionProvider.bounds] and owner display name from
 * [OwnerNameResolver]; all formatting is left to the infra renderer.
 */
@Service
class StallInfoService(
    private val stalls: StallRepository,
    private val regions: RegionProvider,
    private val owners: OwnerNameResolver,
) {
    fun infoFor(id: StallId): StallInfo? {
        val stall = stalls.findById(id) ?: return null
        val bounds = regions.bounds(stall.world, stall.regionId)
        return StallInfo(
            stallId = stall.id.value,
            kind = stall.kind,
            ownerName = ownerName(stall),
            memberCount = stall.members.size,
            currentRent = currentRent(stall),
            nextRentAt = stall.nextRentAt,
            width = bounds?.width ?: 0,
            height = bounds?.height ?: 0,
            length = bounds?.length ?: 0,
            state = stall.state,
            available = stall.state == StallState.UNOWNED,
        )
    }

    private fun ownerName(stall: Stall): String =
        if (stall.owner.type == net.badgersmc.em.domain.stall.OwnerType.NONE) "—"
        else owners.displayNameFor(stall.owner)

    private fun currentRent(stall: Stall): Long =
        stall.rentTerms.dailyRent(stall.winningBid)
}