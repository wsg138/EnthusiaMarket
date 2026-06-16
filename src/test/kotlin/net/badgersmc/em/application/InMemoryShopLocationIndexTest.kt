package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PERF-1 (REQ-282): the in-memory shop-container index reflects exactly the shops put into it,
 * keyed by container coordinate. Red until [InMemoryShopLocationIndex] is implemented.
 */
class InMemoryShopLocationIndexTest {

    private val owner = UUID.randomUUID()

    private fun testShop(
        id: Long = 1,
        cw: String = "world",
        cx: Int = 10,
        cy: Int = 64,
        cz: Int = 20,
    ): Shop = Shop(
        id = id,
        stallId = "stall_01",
        owner = owner,
        signWorld = "world", signX = 0, signY = 64, signZ = 0,
        containerWorld = cw, containerX = cx, containerY = cy, containerZ = cz,
        sellItem = "itemBase64", sellAmount = 1,
        costItem = "costBase64", costAmount = 10,
    )

    @Test
    fun `shopsAt is empty before population`() {
        val index = InMemoryShopLocationIndex()
        assertTrue(index.shopsAt("world", 10, 64, 20).isEmpty())
    }

    @Test
    fun `put then shopsAt returns the shop at its container coordinate only`() {
        val index = InMemoryShopLocationIndex()
        val shop = testShop()
        index.put(shop)
        assertEquals(listOf(shop), index.shopsAt("world", 10, 64, 20))
        assertTrue(index.shopsAt("world", 11, 64, 20).isEmpty(), "adjacent coordinate must not match")
        assertTrue(index.shopsAt("world_nether", 10, 64, 20).isEmpty(), "other world must not match")
    }

    @Test
    fun `rebuild replaces all prior contents`() {
        val index = InMemoryShopLocationIndex()
        index.put(testShop(id = 1, cx = 10))
        index.rebuild(listOf(testShop(id = 2, cx = 30)))
        assertTrue(index.shopsAt("world", 10, 64, 20).isEmpty(), "old entry must be gone after rebuild")
        assertEquals(2L, index.shopsAt("world", 30, 64, 20).single().id)
    }

    @Test
    fun `remove deletes the shop from its coordinate`() {
        val index = InMemoryShopLocationIndex()
        val shop = testShop()
        index.put(shop)
        index.remove(shop)
        assertTrue(index.shopsAt("world", 10, 64, 20).isEmpty())
    }

    @Test
    fun `multiple shops can share a container coordinate`() {
        val index = InMemoryShopLocationIndex()
        index.put(testShop(id = 1))
        index.put(testShop(id = 2))
        assertEquals(setOf(1L, 2L), index.shopsAt("world", 10, 64, 20).map { it.id }.toSet())
    }

    @Test
    fun `remove matches by id even when a non-key field has diverged`() {
        // Reproduces the ghost-entry bug: updateStock changes the persisted stockCount without
        // re-indexing, so a later delete passes a DB-sourced Shop whose stockCount differs from the
        // indexed copy. Removal must match on stable id, not full-object equality.
        val index = InMemoryShopLocationIndex()
        val indexed = testShop(id = 7)
        index.put(indexed)

        index.remove(indexed.copy(stockCount = indexed.stockCount + 99))

        assertTrue(
            index.shopsAt("world", 10, 64, 20).isEmpty(),
            "remove must delete by id, not full-object equality, or stale ghost entries survive",
        )
    }
}
