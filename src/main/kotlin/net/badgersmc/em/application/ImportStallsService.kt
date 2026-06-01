package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.ports.RegionProvisioner
import net.badgersmc.em.domain.stall.*
import net.badgersmc.nexus.annotations.Service

@Service
class ImportStallsService(
    private val regions: RegionProvider,
    private val stalls: StallRepository,
    private val defaultRent: RentTerms,
    private val provisioner: RegionProvisioner,
    private val stallPriority: Int,
) {
    data class Result(val created: Int, val skipped: Int, val provisioned: Int)

    fun import(world: String, prefix: String): Result {
        var created = 0
        var skipped = 0
        var provisioned = 0
        for (ref in regions.listByPrefix(world, prefix)) {
            // Provision flags on EVERY matched region (idempotent) so both
            // new and previously-imported stalls get correct build rights.
            if (provisioner.provision(ref.world, ref.id, stallPriority)) {
                provisioned++
            }
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
                    rentTerms = defaultRent,
                )
            )
            created++
        }
        return Result(created, skipped, provisioned)
    }
}
