package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.events.GuildTradePolicyChangedEvent
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component as TextComponent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

@Component
class GuildPolicyAnnounceListener(
    private val guildProvider: GuildProvider,
    private val lang: LangService,
    private val config: EnthusiaMarketConfig,
) : Listener {
    private val lastAnnounce = mutableMapOf<String, Long>()

    @EventHandler
    fun onPolicyChanged(event: GuildTradePolicyChangedEvent) {
        if (!config.guildPolicy.announceEnabled) return
        if (onCooldown(event.ownerGuildId)) return
        buildMessage(event)?.let { Bukkit.broadcast(it) }
    }

    internal fun onCooldown(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val window = config.guildPolicy.announceCooldownSeconds * 1000L
        val last = lastAnnounce[key]
        if (last != null && now - last < window) return true
        lastAnnounce[key] = now
        return false
    }

    private fun name(id: String): String = guildProvider.guildById(id)?.name ?: id

    internal fun buildMessage(event: GuildTradePolicyChangedEvent): TextComponent? {
        val owner = name(event.ownerGuildId)
        val target = name(event.targetGuildId)
        return when {
            event.action == GuildTradePolicyChangedEvent.Action.CLEARED ->
                lang.msg("guildpolicy.announce.cleared", "owner" to owner, "target" to target)
            event.kind == PolicyKind.EMBARGO ->
                lang.msg("guildpolicy.announce.embargo", "owner" to owner, "target" to target)
            event.kind == PolicyKind.TARIFF ->
                lang.msg("guildpolicy.announce.tariff", "owner" to owner, "target" to target, "rate" to event.ratePct)
            else -> null
        }
    }
}
