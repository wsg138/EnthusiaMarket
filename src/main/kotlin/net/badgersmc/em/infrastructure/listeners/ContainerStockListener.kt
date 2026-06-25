package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
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

/**
 * Keeps shop sign stock text in sync with linked container inventories.
 *
 * **Trade path** — [onTransaction] fires after every successful [PostShopTransactionEvent]:
 * recomputes raw stock, persists [ShopRepository.updateStock], and updates the sign.
 *
 * **Timer path** — [refreshAllSigns] is called every 20 ticks from [EnthusiaMarket.onEnable].
 * Iterates all shops, reads the live container inventory for loaded chunks only (never
 * force-loads), and updates sign + denormalized stock_count when the raw stock changes.
 * This catches stock drift from shift-click, hopper, or other-plugin inventory mutations
 * without needing per-event listeners.
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

    // ── Trade path ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTransaction(event: PostShopTransactionEvent) {
        val shop = shopRepository.findById(event.shopId) ?: return
        val container = loadedContainer(shop) ?: return
        val rawStock = rawStockOf(container.inventory, shop)
        val trades = rawStock / shop.sellAmount.coerceAtLeast(1)
        lastRawStock[shop.id] = rawStock
        shopRepository.updateStock(shop.id, rawStock)
        val sign = loadedSign(shop) ?: return
        updateSignStock(sign, trades)
        trackDepletion(shop, trades)
    }

    // ── Timer path (called from EnthusiaMarket.onEnable every 20t) ─────

    /** Recompute stock for every shop whose container chunk is loaded. */
    fun refreshAllSigns() {
        for (shop in shopRepository.all()) {
            val inventory = containerInventoryIfLoaded(shop) ?: continue
            refreshOne(shop, inventory)
        }
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
        shopRepository.updateStock(shop.id, rawStock)
        trackDepletion(shop, trades)

        // Best-effort sign update — if the sign chunk isn't loaded,
        // the text will catch up on the next tick when it loads.
        loadedSign(shop)?.let { updateSignStock(it, trades) }
    }

    /** The shop's container inventory, or null if the world/chunk/block is unavailable.
     *  Never force-loads a chunk — the [isChunkLoaded] guard is required because
     *  [World.getBlockAt] loads the chunk synchronously when it isn't in memory. */
    private fun containerInventoryIfLoaded(shop: Shop): Inventory? {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return null
        if (!world.isChunkLoaded(shop.containerX shr 4, shop.containerZ shr 4)) return null
        val container = world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ)
            .state as? Container ?: return null
        return container.inventory
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun rawStockOf(inventory: Inventory, shop: Shop): Int {
        val sellStack = ItemStackSerializer.deserialize(shop.sellItem) ?: return 0
        return inventory.contents.filterNotNull()
            .filter { it.isSimilar(sellStack) }
            .sumOf { it.amount }
    }

    /** The shop's container block state. Returns null if the container chunk
     *  isn't loaded — the [isChunkLoaded] guard prevents synchronous chunk loads. */
    private fun loadedContainer(shop: Shop): Container? {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return null
        if (!world.isChunkLoaded(shop.containerX shr 4, shop.containerZ shr 4)) return null
        return world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ)
            .state as? Container
    }

    /** The shop's sign block state, or null if the sign chunk isn't loaded.
     *  The [isChunkLoaded] guard is mandatory — [World.getBlockAt] force-loads. */
    private fun loadedSign(shop: Shop): Sign? {
        val world = Bukkit.getWorld(shop.signWorld) ?: return null
        if (!world.isChunkLoaded(shop.signX shr 4, shop.signZ shr 4)) return null
        return world.getBlockAt(shop.signX, shop.signY, shop.signZ)
            .state as? Sign
    }

    private fun updateSignStock(state: Sign, trades: Int) {
        state.line(3, lang.msg("container_sign.stock_line", "trades" to trades))
        state.update(true)
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
