package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.annotations.Service
import java.util.UUID

/**
 * Application-layer service for guild-owned shop registration (TDD-76, REQ-026).
 *
 * Allows guild-authorized players to register existing shops as guild-owned,
 * and to unregister them, transferring the shop back to player-only ownership.
 */
@Service
class ShopGuildService(
    private val shopRepository: ShopRepository
) {

    /**
     * Registers an existing player-owned shop as a guild-owned shop.
     *
     * @param shopId  The ID of the shop to register.
     * @param guildId The guild that will own the shop.
     * @param playerId The Discord user who created the shop registration.
     * @return [Result.success] with the updated [Shop] on success,
     *         [Result.failure] if the shop does not exist or is already guild-owned.
     */
    fun registerGuildShop(shopId: Long, guildId: UUID, playerId: UUID): Result<Shop> {
        val shop = shopRepository.findById(shopId)
            ?: return Result.failure(IllegalArgumentException("Shop not found: $shopId"))

        if (shop.guildId != null) {
            return Result.failure(IllegalStateException("Shop $shopId is already guild-owned"))
        }

        val updated = shopRepository.setGuildOwnership(shopId, guildId, playerId)
            ?: return Result.failure(IllegalStateException("Failed to set guild ownership for shop $shopId"))

        return Result.success(updated)
    }

    /**
     * Removes guild ownership from a guild-owned shop, returning it to player-only ownership.
     *
     * @param shopId The ID of the shop to unregister.
     * @return [Result.success] with the updated [Shop] on success,
     *         [Result.failure] if the shop does not exist or is not guild-owned.
     */
    fun unregisterGuildShop(shopId: Long): Result<Shop> {
        val shop = shopRepository.findById(shopId)
            ?: return Result.failure(IllegalArgumentException("Shop not found: $shopId"))

        if (shop.guildId == null) {
            return Result.failure(IllegalStateException("Shop $shopId is not guild-owned"))
        }

        val updated = shopRepository.removeGuildOwnership(shopId)
            ?: return Result.failure(IllegalStateException("Failed to remove guild ownership for shop $shopId"))

        return Result.success(updated)
    }

    /**
     * Finds all shops registered to the given guild.
     */
    fun findGuildShops(guildId: UUID): List<Shop> {
        return shopRepository.findByGuildId(guildId)
    }

    /**
     * Returns whether the shop at the given ID is guild-owned.
     */
    fun isGuildShop(shopId: Long): Boolean {
        val shop = shopRepository.findById(shopId)
        return shop?.guildId != null
    }
}
