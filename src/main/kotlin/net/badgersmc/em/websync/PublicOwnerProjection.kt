package net.badgersmc.em.websync

import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import org.bukkit.Bukkit
import java.util.UUID

/** Projects public owner visuals independently from stall/shop serialization. */
class PublicOwnerProjection(
    private val guilds: GuildProvider,
    private val avatars: PublicOwnerAvatarResolver = PublicOwnerAvatarResolver(),
    private val playerName: (UUID) -> String? = { Bukkit.getOfflinePlayer(it).name },
) {
    data class Result(val owner: PublicOwner, val unresolved: Boolean)

    fun project(owner: OwnerRef): Result = when (owner.type) {
        OwnerType.NONE -> Result(PublicOwner("NONE", null, null, "Unowned", avatar = PublicAvatar("NONE")), false)
        OwnerType.SOLO -> player(owner)
        OwnerType.GUILD -> guild(owner)
    }

    private fun player(owner: OwnerRef): Result {
        val uuid = runCatching { UUID.fromString(owner.id) }.getOrNull()
        val resolved = uuid?.let(playerName)
        val name = if (uuid == null) "Unknown Player" else PublicNameResolver.player(uuid) { resolved }
        val head = uuid?.let { avatars.resolve(it, resolved) }
        return Result(
            PublicOwner(
                "PLAYER", owner.id, uuid?.toString(), name.take(64), head?.url,
                PublicAvatar("MINECRAFT_HEAD", head?.source ?: "UNRESOLVED", true, head?.url),
            ),
            resolved == null,
        )
    }

    private fun guild(owner: OwnerRef): Result {
        val guild = guilds.guildById(owner.id)
        val visual = guilds.visualById(owner.id)
        val banner = visual?.banner?.let(::banner)
        val leaderName = visual?.leaderId?.let(playerName)
        val leaderHead = visual?.leaderId?.let { avatars.resolve(it, leaderName) }
        return Result(
            PublicOwner(
                "GUILD", owner.id, null, PublicNameResolver.guild(owner.id) { guild?.name }.take(64),
                avatarUrl = if (banner == null) leaderHead?.url else null,
                avatar = when {
                    banner != null -> PublicAvatar("GUILD_BANNER", "LUMAGUILDS", banner = banner)
                    leaderHead?.url != null -> PublicAvatar("MINECRAFT_HEAD", leaderHead.source, true, leaderHead.url)
                    else -> PublicAvatar("GUILD")
                },
            ),
            guild == null,
        )
    }

    private fun banner(design: GuildProvider.BannerDesign) = PublicBannerDesign(
        design.baseColor,
        design.patterns.take(MAX_BANNER_PATTERNS).map { PublicBannerPattern(it.type, it.color) },
    )

    private companion object {
        const val MAX_BANNER_PATTERNS = 6
    }
}
