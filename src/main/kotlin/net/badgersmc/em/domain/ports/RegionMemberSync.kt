package net.badgersmc.em.domain.ports

import java.util.UUID

/**
 * Outbound port mirroring [net.badgersmc.em.domain.stall.Stall.members]
 * mutations onto the underlying WorldGuard region. Implementations live
 * in the infrastructure layer (see WorldGuardRegionMemberSync). Domain
 * code orchestrates calls; the port keeps Stall free of any WG type
 * dependency. See REQ-202, REQ-203.
 */
interface RegionMemberSync {
    /** Grant [player] member rights in the region identified by [world]/[regionId]. */
    fun addMember(world: String, regionId: String, player: UUID)

    /** Revoke [player]'s member rights. No-op when not currently a member. */
    fun removeMember(world: String, regionId: String, player: UUID)

    /**
     * Replace the region's owner set with exactly [player]. Used when a
     * stall changes hands via buyout/auction/award so the new owner
     * gets WG build/interact rights without needing to be op. Existing
     * owners are cleared first.
     */
    fun setOwner(world: String, regionId: String, player: UUID)

    /**
     * Clear every owner and member from the region — used on sellback /
     * eviction so the next claimant gets a fresh slate and the previous
     * owner can no longer build in a region they don't own.
     */
    fun clearOwnersAndMembers(world: String, regionId: String)
}
