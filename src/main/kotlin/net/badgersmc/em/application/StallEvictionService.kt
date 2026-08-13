package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.ports.SchematicService
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.nexus.annotations.Service
import java.util.logging.Logger

/**
 * Admin force-unclaim of a stall (the `/em evict` command). Resets an OWNED or
 * GRACE stall to UNOWNED, wipes bound shops, strips WorldGuard owner/members,
 * restores the pre-claim geometry (REQ-271, when schematics are enabled), and
 * fires StallStateChangedEvent. No refund — this is an operator action,
 * mirroring the rent-default eviction in RentCollectionService. Shops are wiped
 * (M-4, audit 2026-06-09) so the next buyer never inherits the evicted owner's
 * live shops.
 */
@Service
class StallEvictionService(
    private val stalls: StallRepository,
    private val shops: net.badgersmc.em.domain.shop.ShopRepository,
    private val regionMembers: RegionMemberSync,
    private val config: EnthusiaMarketConfig,
    private val schematics: SchematicService = SchematicService.Disabled,
    private val ipLimiter: IpLimiter,
) {
    private val log = Logger.getLogger(StallEvictionService::class.java.name)

    sealed interface Result {
        /** Stall was owned and is now UNOWNED. */
        data object Evicted : Result
        data object NotFound : Result
        /** Stall was not in an owned state (already UNOWNED / auctioning). */
        data object NotOwned : Result
    }

    fun evict(stallId: StallId): Result {
        val stall = stalls.findById(stallId) ?: return Result.NotFound
        if (stall.state != StallState.OWNED && stall.state != StallState.GRACE) {
            return Result.NotOwned
        }
        val previous = stall.state
        stalls.save(
            stall.copy(
                state = StallState.UNOWNED,
                owner = OwnerRef.unowned(),
                ownerSince = null,
                winningBid = 0L,
                members = emptySet(),
                nextRentAt = null,
            )
        )
        ipLimiter.releaseStallByOwnerId(stall.owner.id)
        // M-4 — wipe shops bound to the stall (parity with sellback) so the
        // next buyer never inherits the evicted owner's live shops.
        deleteBoundShops(stall.id.value)
        clearRegion(stall.world, stall.regionId, stall.id.value)
        fireStateChanged(stall.id.value, previous, StallState.UNOWNED)
        restoreGeometry(stall.id.value, stall.world, stall.regionId)
        return Result.Evicted
    }

    private fun deleteBoundShops(stallId: String) {
        for (shop in shops.findByStall(stallId)) {
            try {
                shops.delete(shop.id)
            } catch (e: RuntimeException) {
                log.warning(
                    "Evict: failed to delete shop ${shop.id} bound to stall " +
                        "$stallId; continuing. cause=${e.message}"
                )
            }
        }
    }

    private fun clearRegion(world: String, regionId: String, stallId: String) {
        try {
            regionMembers.clearOwnersAndMembers(world, regionId)
        } catch (e: RuntimeException) {
            log.warning("Evict: WG owner/member clear failed for $stallId: ${e.message}")
        }
    }

    private fun restoreGeometry(stallId: String, world: String, regionId: String) {
        if (!config.schematics.enabled) return
        val restore = schematics.restore(stallId, world, regionId)
        if (restore is SchematicService.Result.Failure) {
            log.warning(
                "Evict: schematic restore failed for $stallId; " +
                    "geometry left as-is. cause=${restore.cause.message}"
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fireStateChanged(stallId: String, previous: StallState, current: StallState) {
        try {
            org.bukkit.Bukkit.getServer()?.pluginManager?.callEvent(
                StallStateChangedEvent(stallId, previous, current)
            )
        } catch (e: Exception) {
            log.warning("Evict: failed to fire StallStateChangedEvent for $stallId: ${e.message}")
        }
    }
}
