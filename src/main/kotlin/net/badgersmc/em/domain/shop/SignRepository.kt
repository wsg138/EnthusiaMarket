package net.badgersmc.em.domain.shop

import net.badgersmc.em.domain.stall.StallId

interface SignRepository {
    fun findById(id: Long): ShopSign?
    fun byStall(stallId: StallId): List<ShopSign>
    fun bySignLocation(loc: String): ShopSign?
    fun create(sign: ShopSign): Long
    fun save(sign: ShopSign)
    fun delete(id: Long)
}
