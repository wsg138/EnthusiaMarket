package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.ItemStackMatch
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.events.PostShopTransactionEvent
import net.badgersmc.em.events.ShopStockDepletedEvent
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.paper.listeners.Listener
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.inventory.Inventory
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps shop sign stock text in sync with linked container inventories.
 *
 * **Trade path** — [onTransaction] fires after every successful [PostShopTransactionEvent]:
 * recomputes raw stock, stages the DB write for the next timer flush, and updates the sign.
 *
 * **Timer path** — [refreshBatch] is called every tick from [EnthusiaMarket.onEnable].
 * Processes 50 shops per tick with a rotating cursor so a full cycle completes in
 * ~1s at current scale and ~3s at 3000+ shops. Flushes batched stock writes to
 * SQLite at cycle end. This catches stock drift from shift-click, hopper, or
 * other-plugin inventory mutations without needing per-event listeners.
 */
@Listener
@Component
class ContainerStockListener(
    private val shopRepository: ShopRepository,
    private val lang: LangService
) : org.bukkit.event.Listener {

    /** shopId → last-persisted raw stock (dedup: skip sign update if unchanged). */
    private val lastRawStock: MutableMap<Long, Int> = mutableMapOf()
    private var previouslyDepletedShops: MutableSet<Long> = mutableSetOf()

    /** shopId → pending raw stock to flush to DB on next timer tick. */
    private val dirtyStock: ConcurrentHashMap<Long, Int> = ConcurrentHashMap()

    // ── Trade path ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTransaction(event: PostShopTransactionEvent) {
        val shop = shopRepository.findById(event.shopId) ?: return
        val inventory = liveContainerInventory(shop) ?: return
        val rawStock = rawStockOf(inventory, shop)
        val trades = rawStock / shop.sellAmount.coerceAtLeast(1)
        lastRawStock[shop.id] = rawStock
        // Defer DB write — flushed in batch on the next timer tick (PERF-5).
        dirtyStock[shop.id] = rawStock
        trackDepletion(shop, trades)
        loadedSign(shop)?.let { updateSignStock(it, shop, trades) }
    }

    // ── Timer path (called from EnthusiaMarket.onEnable every tick) ─────

    /** Shops per tick for incremental refresh (scales to 1000+ shops). */
    private var cursor = 0
    /** Cached shop list — refreshed once per full cycle to avoid querying the
     *  DB every tick for the same data. Invalidation is triggered by cursor
     *  wrap (cycle end) so new shops are picked up within one full cycle. */
    private var cachedShops: List<Shop> = emptyList()

    /** Recompute stock for a batch of shops and flush on cycle completion. */
    fun refreshBatch(batchSize: Int = 50) {
        // Only re-query the full shop list at cycle start — not every tick.
        // With 200-300 shops, this cuts DB queries from 20/s to ~0.25/s.
        if (cachedShops.isEmpty() || cursor == 0) {
            cachedShops = shopRepository.all().toList()
        }
        val shops = cachedShops
        if (shops.isEmpty()) return
        val end = (cursor + batchSize).coerceAtMost(shops.size)
        for (i in cursor until end) {
            val shop = shops[i]
            val inventory = liveContainerInventory(shop) ?: continue
            refreshOne(shop, inventory)
        }
        cursor = if (end >= shops.size) 0 else end
        if (cursor == 0) flushDirtyStock()  // full cycle complete → persist
    }

    /** Recompute stock for every shop whose container chunk is loaded (admin resync). */
    fun refreshAllSigns() {
        for (shop in shopRepository.all()) {
            val inventory = liveContainerInventory(shop) ?: continue
            refreshOne(shop, inventory)
        }
        flushDirtyStock()
    }

    /** Recompute + persist + sign-update for a single shop whose inventory is known to be loaded. */
    private fun refreshOne(shop: Shop, inventory: Inventory) {
        val rawStock = rawStockOf(inventory, shop)
        if (rawStock == lastRawStock[shop.id]) return                      // unchanged → skip

        // Persist stock_count to DB regardless of sign availability —
        // /shop search reads shop.stockCount (V019). This is the whole
        // point of the denormalized column: search results must stay
        // accurate even when the sign chunk happens to be unloaded.
        lastRawStock[shop.id] = rawStock
        val trades = rawStock / shop.sellAmount.coerceAtLeast(1)
        dirtyStock[shop.id] = rawStock
        trackDepletion(shop, trades)

        // Best-effort sign update — if the sign chunk isn't loaded,
        // the text will catch up on the next tick when it loads.
        loadedSign(shop)?.let { updateSignStock(it, shop, trades) }
    }

    /** Flush all batched [dirtyStock] writes to SQLite in a single batch (PERF-5). */
    private fun flushDirtyStock() {
        if (dirtyStock.isEmpty()) return
        val batch = HashMap(dirtyStock)
        dirtyStock.clear()
        shopRepository.updateStockBatch(batch)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Returns the LIVE inventory of the shop's container, bypassing the
     *  [org.bukkit.block.Block.getState] snapshot cache. Shift-click, hopper,
     *  and other inventory mutations update the underlying NMS tile entity
     *  but not the Bukkit [org.bukkit.block.BlockState] cache, causing the
     *  timer path to read stale contents.
     *
     *  PERF-5: material check before [block.state] avoids unnecessary
     *  snapshot creation for non-container block types. */
    private fun liveContainerInventory(shop: Shop): Inventory? {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return null
        if (!world.isChunkLoaded(shop.containerX shr 4, shop.containerZ shr 4)) return null
        val block = world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ)

        // Material pre-check avoids snapshot for non-container blocks.
        return (block.state as? Container)?.inventory
    }

    private fun rawStockOf(inventory: Inventory, shop: Shop): Int {
        val sellStack = ItemStackSerializer.deserialize(shop.sellItem) ?: return 0
        return ItemStackMatch.countSimilar(inventory, sellStack)
    }

    /** The shop's sign block state, or null if the sign chunk isn't loaded.
     *  The [isChunkLoaded] guard is mandatory — [org.bukkit.World.getBlockAt] force-loads. */
    private fun loadedSign(shop: Shop): Sign? {
        val world = Bukkit.getWorld(shop.signWorld) ?: return null
        if (!world.isChunkLoaded(shop.signX shr 4, shop.signZ shr 4)) return null
        return world.getBlockAt(shop.signX, shop.signY, shop.signZ)
            .state as? Sign
    }

    /** PERF-5: no-physics update — sign text change doesn't need block physics recalculation. */
    private fun updateSignStock(state: Sign, shop: Shop, trades: Int) {
        state.line(3, lang.msg("container_sign.stock_line", "trades" to trades))
        // Red header when SELL shop is out of stock
        if (shop.direction == SignDirection.SELL) {
            val headerKey = if (trades == 0) "sign_header.sell_out" else "sign_header.sell"
            state.line(0, lang.msg(headerKey))
        }
        state.update(false)
    }

    private fun trackDepletion(shop: Shop, trades: Int) {
        if (trades == 0) {
            if (shop.id !in previouslyDepletedShops) {
                previouslyDepletedShops.add(shop.id)
                Bukkit.getPluginManager().callEvent(ShopStockDepletedEvent(shop.owner))
            }
        } else {
            previouslyDepletedShops.remove(shop.id)
        }
    }
}
