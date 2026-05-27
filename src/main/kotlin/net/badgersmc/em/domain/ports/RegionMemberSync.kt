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
}
