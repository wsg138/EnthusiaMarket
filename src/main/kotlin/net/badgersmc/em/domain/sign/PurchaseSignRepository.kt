package net.badgersmc.em.domain.sign

import net.badgersmc.em.domain.stall.StallId

/** Repository for [PurchaseSign] aggregates (REQ-250). */
interface PurchaseSignRepository {
    fun findAt(world: String, x: Int, y: Int, z: Int): PurchaseSign?
    fun findByStall(stallId: StallId): List<PurchaseSign>
    fun all(): List<PurchaseSign>
    fun save(sign: PurchaseSign)
    fun deleteAt(world: String, x: Int, y: Int, z: Int)
}
