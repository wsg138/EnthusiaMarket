package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.em.domain.ports.GuildProvider
import java.util.UUID

class LumaGuildsGuildProvider : GuildProvider {
    private val dissolveHandlers = mutableListOf<(String) -> Unit>()

    override fun guildOf(player: UUID): GuildProvider.GuildRef? =
        TODO("Implemented in Plan 5: Guild integration")

    override fun guildById(id: String): GuildProvider.GuildRef? =
        TODO("Implemented in Plan 5: Guild integration")

    override fun isMember(player: UUID, guildId: String): Boolean =
        TODO("Implemented in Plan 5")

    override fun hasPermission(player: UUID, guildId: String, node: String): Boolean =
        TODO("Implemented in Plan 5")

    override fun bankBalance(guildId: String): Long = TODO("Implemented in Plan 5")
    override fun bankWithdraw(guildId: String, amount: Long): Boolean = TODO("Implemented in Plan 5")
    override fun bankDeposit(guildId: String, amount: Long): Boolean = TODO("Implemented in Plan 5")

    override fun onDissolved(handler: (String) -> Unit) {
        dissolveHandlers.add(handler)
    }
}
