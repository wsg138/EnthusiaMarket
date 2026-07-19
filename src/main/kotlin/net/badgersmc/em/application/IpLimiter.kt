package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.nexus.annotations.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * IP-based fairness limiter for auctions and stall ownership.
 *
 * Two independent rules, both gated by config:
 * - **oneAuctionPerIp**: an IP can only bid on one auction at a time.
 *   Released when the auction settles or is cancelled.
 * - **oneStallPerIp**: an IP can only own one stall at a time.
 *   Released when the stall is sold, evicted, or transferred.
 *
 * All state is in-memory — lost on restart. This is acceptable for
 * an anti-abuse measure where the window of vulnerability is the
 * restart itself.
 */
@Service
class IpLimiter(private val config: EnthusiaMarketConfig) {

    data class Reservation internal constructor(
        internal val kind: Kind,
        internal val ip: String,
        internal val target: String,
        internal val newlyAcquired: Boolean,
    ) {
        internal enum class Kind { AUCTION, STALL }
    }

    data class Attempt(val allowed: Boolean, val reservation: Reservation?)

    /** IP → auction ID. An IP in this map has an active bid on that auction. */
    private val auctionBindings: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    /** IP → owner UUID. An IP in this map already owns a stall. */
    private val stallOwners: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    // ---- Auction binding ----

    /**
     * Try to bind [ip] to [auctionId]. Returns true if allowed:
     * the IP has no binding, or is already bound to this auction.
     */
    fun tryBindAuction(ip: String, auctionId: String): Boolean {
        return acquireAuction(ip, auctionId).allowed
    }

    fun acquireAuction(ip: String, auctionId: String): Attempt {
        if (!config.ipLimiter.oneAuctionPerIp) return Attempt(true, null)
        val existing = auctionBindings.putIfAbsent(ip, auctionId)
        val allowed = existing == null || existing == auctionId
        return Attempt(allowed, if (allowed) Reservation(Reservation.Kind.AUCTION, ip, auctionId, existing == null) else null)
    }

    /** Release the IP's auction binding (auction settled or cancelled). */
    fun releaseAuction(ip: String) {
        auctionBindings.remove(ip)
    }

    /** Release all IP bindings for a given auction. */
    fun releaseAuctionBindings(auctionId: String) {
        auctionBindings.entries.removeIf { it.value == auctionId }
    }

    // ---- Stall ownership ----

    /**
     * Try to claim a stall for [ip] on behalf of [ownerId].
     * Returns true if allowed: the IP does not already own a stall.
     */
    fun tryClaimStall(ip: String, ownerId: String): Boolean {
        return acquireStall(ip, ownerId).allowed
    }

    fun acquireStall(ip: String, ownerId: String): Attempt {
        if (!config.ipLimiter.oneStallPerIp) return Attempt(true, null)
        val existing = stallOwners.putIfAbsent(ip, ownerId)
        return Attempt(existing == null, if (existing == null) Reservation(Reservation.Kind.STALL, ip, ownerId, true) else null)
    }

    /** Roll back only the exact binding newly created by the failed operation. */
    fun rollback(reservation: Reservation?) {
        if (reservation?.newlyAcquired != true) return
        when (reservation.kind) {
            Reservation.Kind.AUCTION -> auctionBindings.remove(reservation.ip, reservation.target)
            Reservation.Kind.STALL -> stallOwners.remove(reservation.ip, reservation.target)
        }
    }

    /** Release [ip]'s stall ownership (sold, evicted, transferred). */
    fun releaseStall(ip: String) {
        stallOwners.remove(ip)
    }

    /** Release all stall ownership entries for a given owner UUID. */
    fun releaseStallByOwnerId(ownerId: String) {
        stallOwners.entries.removeIf { it.value == ownerId }
    }
}
