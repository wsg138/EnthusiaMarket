package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Material

/**
 * Pure shop-search filter (ItemShops parity SP2). Matches shops by their SELL
 * item material, gated by the per-shop searchEnabled opt-in. Under EM's current
 * Vault-money model only sell matching is active; BUY (search by cost item)
 * needs the barter model (SP3) and always returns false today, so the command
 * keeps the ItemShops mode arg without behaving wrongly.
 */
@Service
class ShopSearchService {

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

    /**
     * Filter [shops] to those matching [query]/[mode], using [sellMaterialOf] to
     * resolve each shop's sell-item material (injected so this is unit-testable
     * without Bukkit ItemStack deserialization). Order preserved.
     */
    fun search(
        query: Material,
        mode: SearchMode,
        shops: List<Shop>,
        sellMaterialOf: (Shop) -> Material?,
    ): List<Shop> = shops.filter { matches(it.searchEnabled, sellMaterialOf(it), query, mode) }
}
