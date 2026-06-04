package net.badgersmc.em.domain.shop

import java.util.UUID

interface ShopVaultRepository {
    fun deposit(owner: UUID, itemBytes: String, amount: Int)
    fun findByOwner(owner: UUID): List<VaultItem>
    /** Remove up to [amount]; deletes the row at zero. Returns amount actually removed. */
    fun withdraw(owner: UUID, itemBytes: String, amount: Int): Int
}
