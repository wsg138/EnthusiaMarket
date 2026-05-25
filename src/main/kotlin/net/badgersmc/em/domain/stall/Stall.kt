package net.badgersmc.em.domain.stall

import net.badgersmc.em.domain.ports.GuildProvider
import java.time.Instant
import java.util.UUID

data class Stall(
    val id: StallId,
    val regionId: String,
    val world: String,
    val state: StallState,
    val owner: OwnerRef,
    val ownerSince: Instant?,
    val winningBid: Long,
    val rentTerms: RentTerms
) {
    fun awardTo(newOwner: OwnerRef, winningBid: Long, at: Instant): Stall {
        require(newOwner.type != OwnerType.NONE) { "Cannot award stall to nobody" }
        require(winningBid > 0) { "Winning bid must be positive" }
        return copy(
            state = StallState.OWNED,
            owner = newOwner,
            ownerSince = at,
            winningBid = winningBid
        )
    }

    /**
     * Checks whether [playerUuid] has management authority over this stall.
     *
     * - **SOLO**: the player must match the owner UUID.
     * - **GUILD**: the player must be a guild member with MANAGE_SHOPS permission.
     * - **NONE** (unowned): always returns false.
     *
     * @param playerUuid  the actor requesting management.
     * @param guildProvider  port used to resolve guild membership & permissions.
     * @return `true` if the player is authorised, `false` otherwise.
     */
    fun canManage(playerUuid: UUID, guildProvider: GuildProvider): Boolean {
        return when (owner.type) {
            OwnerType.NONE -> false
            OwnerType.SOLO -> {
                try {
                    UUID.fromString(owner.id) == playerUuid
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
            OwnerType.GUILD -> {
                guildProvider.isMember(playerUuid, owner.id) &&
                    guildProvider.hasShopPermission(
                        playerUuid,
                        owner.id,
                        GuildProvider.GuildPermission.MANAGE_SHOPS
                    )
            }
        }
    }
}
