package net.badgersmc.em.websync.heads

import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.websync.WebsiteSyncDirtySink
import java.util.UUID

/** Dirties only stalls whose visible owner head changed. Call on the Bukkit thread. */
class BedrockHeadDirtyMarker(
    private val stalls: StallRepository,
    private val guilds: GuildProvider,
    private val dirty: WebsiteSyncDirtySink,
) {
    fun mark(playerId: UUID) {
        stalls.all().forEach { stall ->
            val affected = when (stall.owner.type) {
                OwnerType.SOLO -> runCatching { UUID.fromString(stall.owner.id) }.getOrNull() == playerId
                OwnerType.GUILD -> {
                    val visual = guilds.visualById(stall.owner.id)
                    visual?.banner == null && visual?.leaderId == playerId
                }
                OwnerType.NONE -> false
            }
            if (affected) dirty.markDirty(stall.id.value)
        }
    }
}
