package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent
import java.util.logging.Logger

/**
 * On server startup, resyncs WorldGuard region members for every guild-owned
 * stall. Fixes the gap where WG runtime state (owners/members set via
 * [RegionMemberSync]) is lost on restart, leaving guild members unable to
 * build/break/place in their guild stalls until an admin runs /em rg resync.
 *
 * Runs after [ServerLoadEvent] so all plugins (LumaGuilds, WorldGuard) have
 * finished loading and their APIs are available. A short additional delay
 * ensures DI beans are fully materialized.
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
class GuildStallResyncOnStartup(
    private val stalls: StallRepository,
    private val guildProvider: GuildProvider,
    private val regionMembers: RegionMemberSync,
) : Listener {

    private val log = Logger.getLogger(javaClass.name)

    @EventHandler
    fun onServerLoad(@Suppress("UNUSED_PARAMETER") event: ServerLoadEvent) {
        // Run after a delay so all plugins are fully initialized
        Bukkit.getScheduler().runTaskLater(
            Bukkit.getPluginManager().getPlugin("EnthusiaMarket")!!,
            Runnable { resyncGuildStalls() },
            100L, // ~5 seconds after ServerLoad
        )
    }

    private fun resyncGuildStalls() {
        var synced = 0
        var skipped = 0
        var errors = 0

        for (stall in stalls.all()) {
            // Only resync guild-owned stalls that are actively held
            if (!stall.isActiveGuildStall()) continue

            try {
                val memberUuids = guildProvider.memberIds(stall.owner.id)
                if (memberUuids.isEmpty()) {
                    skipped++
                    continue
                }
                regionMembers.clearOwnersAndMembers(stall.world, stall.regionId)
                regionMembers.syncGuildMembers(stall.world, stall.regionId, memberUuids)
                synced++
            } catch (e: Exception) {
                log.warning(
                    "GuildStallResyncOnStartup: failed to resync WG for stall ${stall.id.value} " +
                        "(guild=${stall.owner.id}): ${e.message}"
                )
                errors++
            }
        }

        log.info(
            "GuildStallResyncOnStartup: resynced WG for $synced guild stall(s), " +
                "$skipped skipped (no members), $errors errors"
        )
    }
}
