package net.badgersmc.em.domain.shop

import java.util.UUID

interface ShopRepository {
    fun upsert(shop: Shop): Shop
    fun findById(id: Long): Shop?
    fun findBySign(world: String, x: Int, y: Int, z: Int): Shop?
    fun findByContainer(world: String, x: Int, y: Int, z: Int): List<Shop>
    fun findByStall(stallId: String): List<Shop>
    fun findByOwner(owner: UUID): List<Shop>
    fun all(): List<Shop>
    fun delete(id: Long)
    fun deleteByContainer(world: String, x: Int, y: Int, z: Int)
}
