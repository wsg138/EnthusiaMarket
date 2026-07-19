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
            if (matchesQuery(item.type, normalized)) {
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

    private fun matchesQuery(material: Material, query: String): Boolean =
        material.name == query || material.name.startsWith(query) || CATEGORY_MATCHERS[query]?.invoke(material) == true

    companion object {
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_CONTAINER_DEPTH = 4
        private const val MAX_ITEMS_SCANNED = 1024

        private val CATEGORY_MATCHERS: Map<String, (Material) -> Boolean> = mapOf(
            "ARMOR" to { it.name.endsWith("_HELMET") || it.name.endsWith("_CHESTPLATE") ||
                it.name.endsWith("_LEGGINGS") || it.name.endsWith("_BOOTS") || it.name.endsWith("_HORSE_ARMOR") ||
                it.name == "ELYTRA" },
            "ARMOUR" to { it.name.endsWith("_HELMET") || it.name.endsWith("_CHESTPLATE") ||
                it.name.endsWith("_LEGGINGS") || it.name.endsWith("_BOOTS") || it.name.endsWith("_HORSE_ARMOR") ||
                it.name == "ELYTRA" },
            "TOOLS" to { it.name.endsWith("_PICKAXE") || it.name.endsWith("_AXE") ||
                it.name.endsWith("_SHOVEL") || it.name.endsWith("_HOE") || it.name.endsWith("_SWORD") ||
                it.name in TOOL_MATERIALS },
            "TOOL" to { it.name.endsWith("_PICKAXE") || it.name.endsWith("_AXE") ||
                it.name.endsWith("_SHOVEL") || it.name.endsWith("_HOE") || it.name.endsWith("_SWORD") ||
                it.name in TOOL_MATERIALS },
            "WEAPONS" to { it.name.endsWith("_SWORD") || it.name.endsWith("_AXE") || it.name in WEAPON_MATERIALS },
            "WEAPON" to { it.name.endsWith("_SWORD") || it.name.endsWith("_AXE") || it.name in WEAPON_MATERIALS },
            "POTIONS" to { it.name in POTION_MATERIALS },
            "POTION" to { it.name in POTION_MATERIALS },
            "FOOD" to { it.isEdible },
            "WOOD" to { "WOOD" in it.name || "LOG" in it.name || "STEM" in it.name || "PLANKS" in it.name },
            "ORES" to { it.name.endsWith("_ORE") || it.name in ORE_MATERIALS },
            "ORE" to { it.name.endsWith("_ORE") || it.name in ORE_MATERIALS },
            "REDSTONE" to { it.name in REDSTONE_MATERIALS },
        )
        private val TOOL_MATERIALS = setOf("SHEARS", "FISHING_ROD", "FLINT_AND_STEEL", "BRUSH", "SPYGLASS")
        private val WEAPON_MATERIALS = setOf("BOW", "CROSSBOW", "TRIDENT", "MACE", "SPEAR")
        private val POTION_MATERIALS = setOf("POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW")
        private val ORE_MATERIALS = setOf("COAL", "RAW_IRON", "RAW_COPPER", "RAW_GOLD", "IRON_INGOT", "COPPER_INGOT",
            "GOLD_INGOT", "GOLD_NUGGET", "DIAMOND", "EMERALD", "LAPIS_LAZULI", "REDSTONE", "NETHER_QUARTZ")
        private val REDSTONE_MATERIALS = setOf("REDSTONE", "REDSTONE_TORCH", "REPEATER", "COMPARATOR", "OBSERVER",
            "PISTON", "STICKY_PISTON", "DISPENSER", "DROPPER", "HOPPER", "DAYLIGHT_DETECTOR", "LECTERN", "TARGET")
    }
}
