package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.websync.WebsiteSyncService
import net.badgersmc.nexus.annotations.Component
import net.lumalyte.lg.domain.events.GuildBannerChangedEvent
import net.lumalyte.lg.domain.events.GuildOwnershipTransferEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.UUID

/** Immediately refreshes website stalls whose public guild visual changed. */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
class GuildVisualChangeListener(
    private val stalls: StallRepository,
    private val websiteSync: WebsiteSyncService,
) : Listener {
    @EventHandler
    fun onBannerChanged(event: GuildBannerChangedEvent) = markGuildStalls(event.guildId)

    @EventHandler
    fun onOwnershipTransferred(event: GuildOwnershipTransferEvent) = markGuildStalls(event.guildId)

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) = markPlayerStalls(event.player.uniqueId)

    internal fun markGuildStalls(guildId: UUID) {
        val id = guildId.toString()
        stalls.all()
            .filter { it.owner.type == OwnerType.GUILD && it.owner.id == id }
            .forEach { websiteSync.markDirty(it.id.value) }
    }

    internal fun markPlayerStalls(playerId: UUID) {
        val id = playerId.toString()
        stalls.all()
            .filter { it.owner.type == OwnerType.SOLO && it.owner.id == id }
            .forEach { websiteSync.markDirty(it.id.value) }
    }
}
