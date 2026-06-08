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
        if (sameBlock(event.from, event.to)) return
        if (!config.guildPolicy.entryWarningEnabled) return
        val to = event.to
        val region = regions.regionAt(to.world.name, to.blockX, to.blockY, to.blockZ)
        handleRegionChange(event.player, to.world.name, region)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { lastRegion.remove(event.player.uniqueId) }

    private fun sameBlock(a: org.bukkit.Location, b: org.bukkit.Location): Boolean =
        a.blockX == b.blockX && a.blockY == b.blockY && a.blockZ == b.blockZ

    private fun handleRegionChange(player: org.bukkit.entity.Player, world: String, region: String?) {
        val uuid = player.uniqueId
        if (region == lastRegion.put(uuid, region)) return // unchanged region → no-op
        region ?: return
        warningFor(world, region, uuid)?.let { player.sendMessage(it) }
    }

    private fun name(id: String): String = guildProvider.guildById(id)?.name ?: id

    /** The warning to show a player standing in [region], or null if none applies. */
    internal fun warningFor(world: String, region: String, playerUuid: UUID): TextComponent? {
        val stall = stalls.findByRegion(world, region)?.takeIf { it.owner.type == OwnerType.GUILD } ?: return null
        val policy = policyService.policyToward(stall.owner.id, playerUuid) ?: return null
        return messageFor(policy, name(stall.owner.id))
    }

    private fun messageFor(policy: net.badgersmc.em.domain.guild.GuildTradePolicy, ownerName: String): TextComponent =
        when (policy.kind) {
            PolicyKind.TARIFF -> lang.msg("guildpolicy.entry.tariff", "owner" to ownerName, "rate" to policy.ratePct)
            PolicyKind.EMBARGO -> lang.msg("guildpolicy.entry.embargo", "owner" to ownerName)
        }
}
