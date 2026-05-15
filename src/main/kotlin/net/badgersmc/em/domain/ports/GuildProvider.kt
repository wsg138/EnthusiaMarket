package net.badgersmc.em.domain.ports

import java.util.UUID

interface GuildProvider {
    data class GuildRef(val id: String, val name: String)

    fun guildOf(player: UUID): GuildRef?
    fun guildById(id: String): GuildRef?
    fun isMember(player: UUID, guildId: String): Boolean
    fun hasPermission(player: UUID, guildId: String, node: String): Boolean

    fun bankBalance(guildId: String): Long
    fun bankWithdraw(guildId: String, amount: Long): Boolean
    fun bankDeposit(guildId: String, amount: Long): Boolean

    /** Register a callback invoked when a guild is dissolved. */
    fun onDissolved(handler: (guildId: String) -> Unit)
}
