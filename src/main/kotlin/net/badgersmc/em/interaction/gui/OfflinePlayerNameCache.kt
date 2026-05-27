package net.badgersmc.em.interaction.gui

import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded in-memory cache for `OfflinePlayer.name` lookups.
 *
 * Resolving an offline player by UUID is potentially a disk read; calling it
 * once per GUI repaint per entry, on the main thread, can stall ticks under
 * load. This cache is consulted off-thread by [AuctionBrowserMenu]'s async
 * pre-fetch task so the main-thread render only deals with already-resolved
 * strings.
 *
 * The cache uses [ConcurrentHashMap] for safe multi-thread access. Entries
 * are evicted when the map exceeds [maxEntries] (very simple "drop oldest
 * insertion" eviction — sufficient for tens of distinct bidders, the
 * realistic scale for a stall auction browser).
 */
class OfflinePlayerNameCache(
    private val maxEntries: Int = 256
) {

    private val cache: ConcurrentHashMap<UUID, String> = ConcurrentHashMap()
    private val insertionOrder: MutableList<UUID> = mutableListOf()
    private val orderLock = Any()

    /**
     * Resolve the name for [uuid], reading from cache when possible and
     * falling back to a Bukkit lookup. Must be called from a background
     * thread — the Bukkit lookup may block on disk I/O for an offline
     * player.
     *
     * Returns `null` only when both the cache miss AND Bukkit's lookup
     * fail (player has never joined and no cached name is available).
     */
    fun resolveOffMainThread(uuid: UUID): String? {
        cache[uuid]?.let { return it }
        val resolved = try {
            Bukkit.getOfflinePlayer(uuid).name
        } catch (_: Exception) {
            null
        }
        if (resolved != null) {
            cache[uuid] = resolved
            synchronized(orderLock) {
                insertionOrder.add(uuid)
                while (insertionOrder.size > maxEntries) {
                    val evict = insertionOrder.removeAt(0)
                    cache.remove(evict)
                }
            }
        }
        return resolved
    }

    /** Read-only cache lookup; safe to call from any thread including main. */
    fun peek(uuid: UUID): String? = cache[uuid]

    /** Mostly for tests. */
    val size: Int get() = cache.size
}
