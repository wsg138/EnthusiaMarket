package net.badgersmc.em.domain.shop

import java.util.UUID

@Suppress("TooManyFunctions")
interface ShopRepository {
    fun upsert(shop: Shop): Shop
    fun findById(id: Long): Shop?
    fun findBySign(world: String, x: Int, y: Int, z: Int): Shop?
    fun findByContainer(world: String, x: Int, y: Int, z: Int): List<Shop>
    fun findByStall(stallId: String): List<Shop>
    fun findByOwner(owner: UUID): List<Shop>
    fun findByGuildId(guildId: UUID): List<Shop>
    fun setGuildOwnership(id: Long, guildId: UUID, creatorId: UUID): Shop?
    fun removeGuildOwnership(id: Long): Shop?
    fun all(): List<Shop>
    fun countAll(): Int
    fun countByOwner(owner: UUID): Int
    fun delete(id: Long)
    fun deleteByContainer(world: String, x: Int, y: Int, z: Int)
    fun deleteByOwner(owner: UUID): Int
    /** Search-enabled shops whose sell item is [material] (UPPERCASE Material name). */
    fun findBySellMaterial(material: String): List<Shop>
    /** Populate sell_material for rows missing it (one-time after V018). Returns rows updated. */
    fun backfillSellMaterials(): Int
    /** Set the denormalized container stock for [id]. */
    fun updateStock(id: Long, stockCount: Int)

    /** Freeze or unfreeze all shops on a stall (bulk, single UPDATE). */
    fun freezeByStall(stallId: String, frozen: Boolean)
}
