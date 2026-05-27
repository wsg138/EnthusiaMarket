package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Service
import java.util.UUID

/**
 * Orchestrates per-stall member-roster mutations.
 *
 * - Authorises the actor against [Stall.canManage] (REQ-202).
 * - Applies the pure domain operation on the [Stall] aggregate.
 * - Mirrors the change to the underlying WorldGuard region via
 *   [RegionMemberSync] so the member actually gains build/interact
 *   rights (REQ-203).
 * - Persists the updated aggregate.
 */
@Service
class StallMemberService(
    private val stalls: StallRepository,
    private val regionMembers: RegionMemberSync,
    private val guildProvider: GuildProvider,
) {

    sealed interface Result {
        data class Success(val stall: Stall) : Result
        data object NotFound : Result
        data object NotAuthorised : Result
        data class Rejected(val reason: String) : Result
    }

    fun addMember(stallId: StallId, actor: UUID, target: UUID): Result {
        val stall = stalls.findById(stallId) ?: return Result.NotFound
        if (!stall.canManage(actor, guildProvider)) return Result.NotAuthorised
        val updated = try {
            stall.addMember(target)
        } catch (e: IllegalStateException) {
            return Result.Rejected(e.message ?: "member cap reached")
        }
        regionMembers.addMember(stall.world, stall.regionId, target)
        stalls.save(updated)
        return Result.Success(updated)
    }

    fun removeMember(stallId: StallId, actor: UUID, target: UUID): Result {
        val stall = stalls.findById(stallId) ?: return Result.NotFound
        if (!stall.canManage(actor, guildProvider)) return Result.NotAuthorised
        val updated = stall.removeMember(target)
        regionMembers.removeMember(stall.world, stall.regionId, target)
        stalls.save(updated)
        return Result.Success(updated)
    }

    fun listMembers(stallId: StallId, actor: UUID): Result {
        val stall = stalls.findById(stallId) ?: return Result.NotFound
        if (!stall.canManage(actor, guildProvider)) return Result.NotAuthorised
        return Result.Success(stall)
    }
}
