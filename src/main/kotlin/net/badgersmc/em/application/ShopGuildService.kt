package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.GuildProvider
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
    private val shopRepository: ShopRepository,
    private val guildProvider: GuildProvider,
) {

    /**
     * Registers an existing player-owned shop as a guild-owned shop.
     *
     * @param shopId  The ID of the shop to register.
     * @param guildId The guild that will own the shop.
     * @param playerId The Discord user who created the shop registration.
     * @return [Result.success] with the updated [Shop] on success,
     *         [Result.failure] if the shop does not exist, is already
     *         guild-owned, is not owned by [playerId], or [playerId] is
     *         not a member of [guildId].
     */
    fun registerGuildShop(shopId: Long, guildId: UUID, playerId: UUID): Result<Shop> {
        val shop = shopRepository.findById(shopId)
            ?: return Result.failure(IllegalArgumentException("Shop not found: $shopId"))

        if (shop.guildId != null) {
            return Result.failure(IllegalStateException("Shop $shopId is already guild-owned"))
        }

        // C5: only the shop's owner can register it as guild-owned.
        if (shop.owner != playerId) {
            return Result.failure(
                IllegalAccessException("Player $playerId does not own shop $shopId")
            )
        }

        // C5: the registering player must be a member of the target guild —
        // otherwise a non-member could bind a shop to a guild they have
        // no authority over.
        if (!guildProvider.isMember(playerId, guildId.toString())) {
            return Result.failure(
                IllegalStateException("Player $playerId is not a member of guild $guildId")
            )
        }

        val updated = shopRepository.setGuildOwnership(shopId, guildId, playerId)
            ?: return Result.failure(IllegalStateException("Failed to set guild ownership for shop $shopId"))

        return Result.success(updated)
    }

    /**
     * Removes guild ownership from a guild-owned shop, returning it to player-only ownership.
     *
     * @param shopId The ID of the shop to unregister.
     * @param actor The player attempting the unregister. Must own the shop
     *              outright, or hold the MANAGE_SHOPS permission in the owning
     *              guild, for the call to succeed.
     * @return [Result.success] with the updated [Shop] on success,
     *         [Result.failure] if the shop does not exist, is not guild-owned,
     *         or the actor lacks ownership / MANAGE_SHOPS permission to unregister it.
     */
    fun unregisterGuildShop(shopId: Long, actor: UUID): Result<Shop> {
        val shop = shopRepository.findById(shopId)
            ?: return Result.failure(IllegalArgumentException("Shop not found: $shopId"))

        if (shop.guildId == null) {
            return Result.failure(IllegalStateException("Shop $shopId is not guild-owned"))
        }

        // C5: actor must either own the shop outright, or have MANAGE_SHOPS
        // permission in the guild that owns it. Non-members / members without
        // the permission must not be able to dissolve a guild's claim on a shop.
        val isShopOwner = shop.owner == actor
        val hasManagePerm = guildProvider.hasShopPermission(
            actor, shop.guildId.toString(), GuildProvider.GuildPermission.MANAGE_SHOPS
        )
        if (!isShopOwner && !hasManagePerm) {
            return Result.failure(
                IllegalAccessException(
                    "Player $actor is not the shop owner nor a MANAGE_SHOPS member of guild ${shop.guildId}"
                )
            )
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
