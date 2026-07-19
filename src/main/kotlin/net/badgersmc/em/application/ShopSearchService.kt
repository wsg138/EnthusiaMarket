package net.badgersmc.em.application

import net.badgersmc.nexus.annotations.Service
import org.bukkit.Material
import org.bukkit.block.ShulkerBox
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta

/**
 * Pure shop-search filter (ItemShops parity SP2). Matches shops by their SELL
 * item material, gated by the per-shop searchEnabled opt-in. Under EM's current
 * Vault-money model only sell matching is active; BUY (search by cost item)
 * needs the barter model (SP3) and always returns false today, so the command
 * keeps the ItemShops mode arg without behaving wrongly.
 */
@Service
class ShopSearchService {

    data class Match(val material: Material, val nested: Boolean)

    enum class SearchMode { SELL, BUY, ANY }

    /** True when a shop with [sellMaterial] (searchEnabled=[searchEnabled]) matches [query] under [mode]. */
    fun matches(searchEnabled: Boolean, sellMaterial: Material?, query: Material, mode: SearchMode): Boolean {
        if (!searchEnabled) return false
        val inSell = sellMaterial != null && sellMaterial == query
        return when (mode) {
            SearchMode.SELL -> inSell
            SearchMode.ANY -> inSell // BUY half needs barter (SP3)
            SearchMode.BUY -> false
        }
    }

    /** Finds an exact or prefix material match in the sold item and its supported containers. */
    fun findMatch(searchEnabled: Boolean, soldItem: ItemStack, query: String): Match? {
        if (!searchEnabled || query.length < MIN_QUERY_LENGTH) return null
        val normalized = query.uppercase()
        var visited = 0

        fun search(item: ItemStack, depth: Int): Match? {
            if (visited++ >= MAX_ITEMS_SCANNED) return null
            if (item.type.name == normalized || item.type.name.startsWith(normalized)) {
                return Match(item.type, depth > 0)
            }
            if (depth >= MAX_CONTAINER_DEPTH) return null
            return containerContents(item).firstNotNullOfOrNull { search(it, depth + 1) }
        }

        return search(soldItem, 0)
    }

    private fun containerContents(item: ItemStack): List<ItemStack> {
        val meta = item.itemMeta
        val shulker = (meta as? BlockStateMeta)?.blockState as? ShulkerBox
        if (shulker != null) {
            return shulker.inventory.contents.filterNotNull().filterNot { it.type.isAir }
        }
        return (meta as? BundleMeta)?.items?.filterNot { it.type.isAir }.orEmpty()
    }

    companion object {
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_CONTAINER_DEPTH = 4
        private const val MAX_ITEMS_SCANNED = 1024
    }
}
