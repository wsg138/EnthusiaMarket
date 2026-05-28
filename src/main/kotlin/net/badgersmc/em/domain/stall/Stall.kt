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
    val rentTerms: RentTerms,
    /**
     * Co-owners delegated build/interact rights inside the stall region.
     * Distinct from [owner]; new stalls start with an empty roster.
     * See REQ-200.
     */
    val members: Set<UUID> = emptySet(),
    /**
     * Per-stall ceiling on [members] size. `-1` means unlimited
     * (default); zero or positive values are enforced on [addMember].
     * See REQ-201.
     */
    val maxMembers: Int = -1,
    /**
     * Wall-clock instant when the next rent collection is due. Set on
     * award and bumped on each successful rent tick / extension. `null`
     * for stalls created before V011 (use ownerSince + interval as the
     * fallback estimate). Purchase-sign renderer reads this to display
     * the time remaining (REQ-250 extension).
     */
    val nextRentAt: Instant? = null,
) {
    /**
     * Add [playerUuid] to the member roster. Idempotent — re-adding an
     * existing member is a no-op. Rejects with [IllegalStateException]
     * when the roster is at its configured cap (REQ-201). The cap only
     * applies to genuinely new entries; re-adding an existing member at
     * cap still succeeds because no slot is consumed.
     */
    fun addMember(playerUuid: UUID): Stall {
        if (playerUuid in members) return this
        // Owner cannot be added to the co-owner roster — they already have
        // full authority and are distinct from members (REQ-200).
        if (owner.type == OwnerType.SOLO) {
            val ownerUuid = runCatching { UUID.fromString(owner.id) }.getOrNull()
            if (ownerUuid == playerUuid) return this
        }
        check(maxMembers < 0 || members.size < maxMembers) {
            "Stall ${id.value} is at its member cap ($maxMembers)"
        }
        return copy(members = members + playerUuid)
    }

    /** Remove [playerUuid] from the roster. No-op when not a member. */
    fun removeMember(playerUuid: UUID): Stall {
        if (playerUuid !in members) return this
        return copy(members = members - playerUuid)
    }

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
