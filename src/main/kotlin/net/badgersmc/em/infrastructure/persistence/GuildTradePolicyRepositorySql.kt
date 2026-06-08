package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.GuildTradePolicyRepository
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.nexus.annotations.Repository
import javax.sql.DataSource

@Repository
class GuildTradePolicyRepositorySql(private val ds: DataSource) : GuildTradePolicyRepository {

    override fun find(ownerGuildId: String, targetGuildId: String): GuildTradePolicy? {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT kind, rate_pct FROM guild_trade_policies WHERE owner_guild_id = ? AND target_guild_id = ?"
            ).use { ps ->
                ps.setString(1, ownerGuildId); ps.setString(2, targetGuildId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.valueOf(rs.getString("kind")), rs.getInt("rate_pct"))
                }
            }
        }
    }

    override fun listByOwner(ownerGuildId: String): List<GuildTradePolicy> {
        val out = mutableListOf<GuildTradePolicy>()
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT target_guild_id, kind, rate_pct FROM guild_trade_policies WHERE owner_guild_id = ?"
            ).use { ps ->
                ps.setString(1, ownerGuildId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        out.add(GuildTradePolicy(ownerGuildId, rs.getString("target_guild_id"),
                            PolicyKind.valueOf(rs.getString("kind")), rs.getInt("rate_pct")))
                    }
                }
            }
        }
        return out
    }

    override fun upsert(policy: GuildTradePolicy) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO guild_trade_policies (owner_guild_id, target_guild_id, kind, rate_pct)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(owner_guild_id, target_guild_id)
                DO UPDATE SET kind = excluded.kind, rate_pct = excluded.rate_pct
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, policy.ownerGuildId); ps.setString(2, policy.targetGuildId)
                ps.setString(3, policy.kind.name); ps.setInt(4, policy.ratePct)
                ps.executeUpdate()
            }
        }
    }

    override fun delete(ownerGuildId: String, targetGuildId: String) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM guild_trade_policies WHERE owner_guild_id = ? AND target_guild_id = ?"
            ).use { ps -> ps.setString(1, ownerGuildId); ps.setString(2, targetGuildId); ps.executeUpdate() }
        }
    }

    override fun deleteAllInvolving(guildId: String) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM guild_trade_policies WHERE owner_guild_id = ? OR target_guild_id = ?"
            ).use { ps -> ps.setString(1, guildId); ps.setString(2, guildId); ps.executeUpdate() }
        }
    }
}
