package net.badgersmc.em.domain.guild

interface GuildTradePolicyRepository {
    fun find(ownerGuildId: String, targetGuildId: String): GuildTradePolicy?
    fun listByOwner(ownerGuildId: String): List<GuildTradePolicy>
    fun upsert(policy: GuildTradePolicy)
    fun delete(ownerGuildId: String, targetGuildId: String)
    /** Delete every policy where [guildId] is the owner OR the target (disband cleanup). */
    fun deleteAllInvolving(guildId: String)
}
