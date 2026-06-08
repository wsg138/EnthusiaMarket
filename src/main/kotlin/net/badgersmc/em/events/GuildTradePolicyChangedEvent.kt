package net.badgersmc.em.events

import net.badgersmc.em.domain.guild.PolicyKind
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired when a guild's trade policy toward another guild is set, changed, or
 * cleared. Listeners broadcast it; the policy write itself is already done.
 * [kind] is null when [action] == CLEARED.
 */
class GuildTradePolicyChangedEvent(
    val ownerGuildId: String,
    val targetGuildId: String,
    val kind: PolicyKind?,
    val ratePct: Int,
    val action: Action,
) : Event() {
    enum class Action { SET, CLEARED }
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}
