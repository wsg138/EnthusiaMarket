package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.ShopVaultRepository
import net.badgersmc.em.domain.shop.VaultItem
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopVaultServiceTest {

    @BeforeTest fun setup() { MockBukkit.mock() }
    @AfterTest fun teardown() { MockBukkit.unmock() }

    /** Tiny in-memory ShopVaultRepository fake. */
    private class FakeVault : ShopVaultRepository {
        val store = mutableMapOf<Pair<UUID, String>, Int>()
        override fun deposit(owner: UUID, itemBytes: String, amount: Int) {
            store[owner to itemBytes] = (store[owner to itemBytes] ?: 0) + amount
        }
        override fun findByOwner(owner: UUID) =
            store.filterKeys { it.first == owner }.map { VaultItem(owner, it.key.second, it.value) }
        override fun withdraw(owner: UUID, itemBytes: String, amount: Int): Int {
            val cur = store[owner to itemBytes] ?: return 0
            val r = minOf(cur, amount)
            if (r >= cur) store.remove(owner to itemBytes) else store[owner to itemBytes] = cur - r
            return r
        }
    }

    @Test fun `deposit then contents round-trips the item`() {
        val repo = FakeVault()
        val svc = ShopVaultService(repo)
        val owner = UUID.randomUUID()
        svc.deposit(owner, ItemStack(Material.DIAMOND), 7)
        val contents = svc.contents(owner)
        assertEquals(1, contents.size)
        assertEquals(Material.DIAMOND, contents.first().first.type)
        assertEquals(7, contents.first().second)
    }

    @Test fun `withdraw returns the amount removed`() {
        val repo = FakeVault()
        val svc = ShopVaultService(repo)
        val owner = UUID.randomUUID()
        svc.deposit(owner, ItemStack(Material.IRON_INGOT), 10)
        assertEquals(4, svc.withdraw(owner, ItemStack(Material.IRON_INGOT), 4))
    }
}
