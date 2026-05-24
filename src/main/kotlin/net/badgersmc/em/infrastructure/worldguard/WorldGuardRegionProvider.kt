package net.badgersmc.em.infrastructure.worldguard

import com.sk89q.worldguard.WorldGuard
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit

@Component
class WorldGuardRegionProvider : RegionProvider {

    override fun listByPrefix(world: String, prefix: String): List<RegionProvider.RegionRef> {
        val bukkitWorld = Bukkit.getWorld(world) ?: return emptyList()
        val container = WorldGuard.getInstance().platform.regionContainer
        val regionManager = container.get(
            com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(bukkitWorld)
        ) ?: return emptyList()
        return regionManager.regions.keys
            .filter { it.startsWith(prefix) }
            .map { RegionProvider.RegionRef(world, it) }
    }

    override fun exists(world: String, id: String): Boolean {
        val bukkitWorld = Bukkit.getWorld(world) ?: return false
        val container = WorldGuard.getInstance().platform.regionContainer
        val regionManager = container.get(
            com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(bukkitWorld)
        ) ?: return false
        return regionManager.getRegion(id) != null
    }
}
