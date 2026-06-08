package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component as TextComponent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

@Component
class GuildShopPolicyEntryListener(
    private val regions: RegionProvider,
    private val stalls: StallRepository,
    private val policyService: GuildTradePolicyService,
    private val guildProvider: GuildProvider,
    private val lang: LangService,
    private val config: EnthusiaMarketConfig,
) : Listener {
    private val lastRegion = mutableMapOf<UUID, String?>()

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return
        if (!config.guildPolicy.entryWarningEnabled) return
        val uuid = event.player.uniqueId
        val region = regions.regionAt(to.world.name, to.blockX, to.blockY, to.blockZ)
        if (region == lastRegion[uuid]) return
        lastRegion[uuid] = region
        if (region == null) return
        warningFor(to.world.name, region, uuid)?.let { event.player.sendMessage(it) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { lastRegion.remove(event.player.uniqueId) }

    /** The warning to show a player standing in [region], or null if none applies. */
    internal fun warningFor(world: String, region: String, playerUuid: UUID): TextComponent? {
        val stall = stalls.findByRegion(world, region) ?: return null
        if (stall.owner.type != OwnerType.GUILD) return null
        val policy = policyService.policyToward(stall.owner.id, playerUuid) ?: return null
        val owner = guildProvider.guildById(stall.owner.id)?.name ?: stall.owner.id
        return when (policy.kind) {
            PolicyKind.TARIFF -> lang.msg("guildpolicy.entry.tariff", "owner" to owner, "rate" to policy.ratePct)
            PolicyKind.EMBARGO -> lang.msg("guildpolicy.entry.embargo", "owner" to owner)
        }
    }
}
