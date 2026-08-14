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

    @Suppress("TooGenericExceptionCaught")
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
        for (shop in shops.findByStall(stall.id.value)) {
            try {
                shops.delete(shop.id)
            } catch (e: Exception) {
                log.warning(
                    "Evict: failed to delete shop ${shop.id} bound to stall " +
                        "${stall.id.value}; continuing. cause=${e.message}"
                )
            }
        }
        try {
            regionMembers.clearOwnersAndMembers(stall.world, stall.regionId)
        } catch (e: Exception) {
            // DB is authoritative; WG can be resynced via /em rg resync.
            log.warning("Evict: WG owner/member clear failed for ${stall.id.value}: ${e.message}")
        }
        fireStateChanged(stall.id.value, previous, StallState.UNOWNED)
        if (config.schematics.enabled) {
            val restore = schematics.restore(stall.id.value, stall.world, stall.regionId)
            if (restore is SchematicService.Result.Failure) {
                log.warning(
                    "Evict: schematic restore failed for ${stall.id.value}; " +
                        "geometry left as-is. cause=${restore.cause.message}"
                )
            }
        }
        return Result.Evicted
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