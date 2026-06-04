package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.ShopVaultRepository
import net.badgersmc.nexus.annotations.Service
import org.bukkit.inventory.ItemStack
import java.util.Base64
import java.util.UUID

/**
 * Per-owner barter vault (ItemShops parity SP3). Items are keyed by their Paper NBT
 * serialization (serializeAsBytes → Base64) — data-component-aware and migrated on
 * read via deserializeBytes, unlike the legacy ItemStackSerializer.
 */
@Service
class ShopVaultService(private val vault: ShopVaultRepository) {

    fun deposit(owner: UUID, item: ItemStack, amount: Int) = vault.deposit(owner, key(item), amount)

    fun withdraw(owner: UUID, item: ItemStack, amount: Int): Int = vault.withdraw(owner, key(item), amount)

    fun contents(owner: UUID): List<Pair<ItemStack, Int>> =
        vault.findByOwner(owner).map { ItemStack.deserializeBytes(Base64.getDecoder().decode(it.itemBytes)) to it.amount }

    private fun key(item: ItemStack): String {
        val one = item.clone().apply { amount = 1 }
        return Base64.getEncoder().encodeToString(one.serializeAsBytes())
    }
}
