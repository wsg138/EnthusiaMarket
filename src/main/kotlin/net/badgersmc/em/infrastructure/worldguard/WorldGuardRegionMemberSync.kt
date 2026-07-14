package net.badgersmc.em.infrastructure.worldguard

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import java.util.UUID
import java.util.logging.Logger

/**
 * WorldGuard implementation of [RegionMemberSync]. Mutates the
 * region's member set so the player gains build/interact rights
 * inside the stall (REQ-203). Silently ignores missing worlds /
 * regions — the domain-side roster remains authoritative, and a
 * later resync (e.g. world reload) can re-attach memberships.
 */
@Component
class WorldGuardRegionMemberSync : RegionMemberSync {

    private val log = Logger.getLogger(javaClass.name)

    override fun addMember(world: String, regionId: String, player: UUID) {
        withRegion(world, regionId) { region ->
            region.members.addPlayer(player)
        }
    }

    override fun removeMember(world: String, regionId: String, player: UUID) {
        withRegion(world, regionId) { region ->
            region.members.removePlayer(player)
        }
    }

    override fun setOwner(world: String, regionId: String, player: UUID) {
        withRegion(world, regionId) { region ->
            // Replace, don't append — a stall transfer shouldn't leave
            // the previous owner with build rights. Use the domain
            // clear-then-add pattern WG exposes via DefaultDomain.
            region.owners.removeAll()
            region.owners.addPlayer(player)
            // Also clear members on owner transfer — old trusted players
            // must be explicitly re-trusted by the new owner.
            region.members.removeAll()
        }
    }

    override fun clearOwnersAndMembers(world: String, regionId: String) {
        withRegion(world, regionId) { region ->
            region.owners.removeAll()
            region.members.removeAll()
        }
    }

    override fun syncGuildMembers(world: String, regionId: String, memberUuids: Set<UUID>) {
        withRegion(world, regionId) { region ->
            region.owners.removeAll()
            region.members.removeAll()
            memberUuids.forEach { region.members.addPlayer(it) }
        }
    }

    private inline fun withRegion(
        world: String,
        regionId: String,
        block: (com.sk89q.worldguard.protection.regions.ProtectedRegion) -> Unit,
    ) {
        val bukkitWorld = Bukkit.getWorld(world)
        if (bukkitWorld == null) {
            log.warning("RegionMemberSync: world $world not loaded; skipping mutation")
            return
        }
        val regionManager = WorldGuard.getInstance().platform.regionContainer
            .get(BukkitAdapter.adapt(bukkitWorld))
        if (regionManager == null) {
            log.warning("RegionMemberSync: no region manager for $world; skipping mutation")
            return
        }
        val region = regionManager.getRegion(regionId)
        if (region == null) {
            log.warning("RegionMemberSync: region $regionId not found in $world; skipping mutation")
            return
        }
        block(region)
    }
}
