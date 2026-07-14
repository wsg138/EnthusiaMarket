package net.badgersmc.em.infrastructure.worldguard

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.RegionGroup
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import net.badgersmc.em.domain.ports.RegionProvisioner
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import java.util.logging.Logger

/**
 * WorldGuard implementation of [RegionProvisioner]. Stamps the stall
 * region with the ARM-equivalent flag set (core build/use/chest/place/
 * break/ride scoped to MEMBERS + use to ALL) plus decoration flags
 * (item-frame-rotation, interact) so item frames and armor stands work.
 * See spec §3 Workstream F.
 */
@Component
class WorldGuardRegionProvisioner : RegionProvisioner {

    private val log = Logger.getLogger(javaClass.name)

    override fun provision(world: String, regionId: String, priority: Int): Boolean {
        val bukkitWorld = Bukkit.getWorld(world)
        if (bukkitWorld == null) {
            log.warning("RegionProvisioner: world $world not loaded; skipping $regionId")
            return false
        }
        val regionManager = WorldGuard.getInstance().platform.regionContainer
            .get(BukkitAdapter.adapt(bukkitWorld))
        if (regionManager == null) {
            log.warning("RegionProvisioner: no region manager for $world; skipping $regionId")
            return false
        }
        val region = regionManager.getRegion(regionId)
        if (region == null) {
            log.warning("RegionProvisioner: region $regionId not found in $world; skipping")
            return false
        }
        applyFlags(region, priority)
        try {
            regionManager.save()
        } catch (e: com.sk89q.worldguard.protection.managers.storage.StorageException) {
            log.warning("RegionProvisioner: flags applied in-memory but save failed for $regionId: ${e.message}")
        }
        return true
    }

    private fun applyFlags(region: ProtectedRegion, priority: Int) {
        region.priority = priority
        // Deny pistons in the stall (REQ-285). Without this, players push blocks/items across the
        // region boundary with pistons to bypass build protection (e.g. shove crystals out of a
        // locked stall). WG's piston flag is region-wide, so owner redstone pistons are disabled too —
        // an accepted trade-off for closing the cross-border exploit.
        region.setFlag(Flags.PISTONS, StateFlag.State.DENY)
        // Core build rights, scoped to region members.
        setMemberAllow(region, Flags.BUILD)
        setMemberAllow(region, Flags.CHEST_ACCESS)
        setMemberAllow(region, Flags.BLOCK_PLACE)
        setMemberAllow(region, Flags.BLOCK_BREAK)
        setMemberAllow(region, Flags.RIDE)
        // Use (buttons/doors/etc.) open to everyone so shoppers can interact.
        region.setFlag(Flags.USE, StateFlag.State.ALLOW)
        region.setFlag(Flags.USE.regionGroupFlag, RegionGroup.ALL)
        // Decoration entities: item frames + armor stands need these so the
        // entity-limit feature governs entities players can actually use.
        region.setFlag(Flags.ITEM_FRAME_ROTATE, StateFlag.State.ALLOW)
        region.setFlag(Flags.ITEM_FRAME_ROTATE.regionGroupFlag, RegionGroup.MEMBERS)
        region.setFlag(Flags.INTERACT, StateFlag.State.ALLOW)
        region.setFlag(Flags.INTERACT.regionGroupFlag, RegionGroup.MEMBERS)
        // Water flow — allow members to place water buckets (default: denied globally)
        region.setFlag(Flags.WATER_FLOW, StateFlag.State.ALLOW)
        region.setFlag(Flags.WATER_FLOW.regionGroupFlag, RegionGroup.MEMBERS)
        // Candle lighting — allow members to light candles/campfires
        region.setFlag(Flags.LIGHTER, StateFlag.State.ALLOW)
        region.setFlag(Flags.LIGHTER.regionGroupFlag, RegionGroup.MEMBERS)
    }

    private fun setMemberAllow(region: ProtectedRegion, flag: StateFlag) {
        region.setFlag(flag, StateFlag.State.ALLOW)
        region.setFlag(flag.regionGroupFlag, RegionGroup.MEMBERS)
    }
}
