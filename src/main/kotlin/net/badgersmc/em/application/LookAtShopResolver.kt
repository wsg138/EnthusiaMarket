package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.annotations.Service

/**
 * Resolves the shop an admin is looking at (ItemShops parity SP5). The caller does
 * the Bukkit raycast (Player.getTargetBlockExact) and passes the block's world +
 * coords; this keeps the lookup logic pure and unit-testable. A sign block resolves
 * directly; otherwise the coords are tried as a linked container (first match).
 */
@Service
class LookAtShopResolver(
    private val shops: ShopRepository,
) {
    fun resolve(world: String?, x: Int, y: Int, z: Int): Shop? {
        if (world == null) return null
        shops.findBySign(world, x, y, z)?.let { return it }
        return shops.findByContainer(world, x, y, z).firstOrNull()
    }
}