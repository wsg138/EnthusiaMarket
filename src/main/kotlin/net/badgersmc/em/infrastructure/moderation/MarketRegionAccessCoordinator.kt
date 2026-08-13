package net.badgersmc.em.infrastructure.moderation

import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.ports.RegionProvider
import net.enthusia.market.api.moderation.MarketOwnership
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal data class MarketRegionAccessSnapshot(
    val world: String,
    val regionId: String,
    val ownershipType: MarketOwnership.Type,
    val ownershipId: String,
    val members: Set<UUID>,
)

internal interface MarketRegionAccessCoordinator {
    fun clear(snapshot: MarketRegionAccessSnapshot)
    fun restore(snapshot: MarketRegionAccessSnapshot)
}

/** Runs every WorldGuard mutation on the server thread and fails on a missing region. */
internal class BukkitMarketRegionAccessCoordinator(
    private val plugin: Plugin,
    private val regions: RegionProvider,
    private val members: RegionMemberSync,
    private val guilds: GuildProvider,
) : MarketRegionAccessCoordinator {
    override fun clear(snapshot: MarketRegionAccessSnapshot) = onServerThread {
        requireRegion(snapshot)
        members.clearOwnersAndMembers(snapshot.world, snapshot.regionId)
    }

    override fun restore(snapshot: MarketRegionAccessSnapshot) = onServerThread {
        requireRegion(snapshot)
        members.clearOwnersAndMembers(snapshot.world, snapshot.regionId)
        when (snapshot.ownershipType) {
            MarketOwnership.Type.NONE -> Unit
            MarketOwnership.Type.SOLO -> {
                members.setOwner(snapshot.world, snapshot.regionId, UUID.fromString(snapshot.ownershipId))
                snapshot.members.forEach { members.addMember(snapshot.world, snapshot.regionId, it) }
            }
            MarketOwnership.Type.GUILD -> members.syncGuildMembers(
                snapshot.world,
                snapshot.regionId,
                guilds.memberIds(snapshot.ownershipId),
            )
        }
    }

    private fun requireRegion(snapshot: MarketRegionAccessSnapshot) {
        check(regions.exists(snapshot.world, snapshot.regionId)) {
            "Market region ${snapshot.world}/${snapshot.regionId} is unavailable"
        }
    }

    private fun onServerThread(action: () -> Unit) {
        if (Bukkit.isPrimaryThread()) {
            action()
            return
        }
        val completion = CompletableFuture<Unit>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            runCatching(action).fold(completion::complete, completion::completeExceptionally)
        })
        completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
