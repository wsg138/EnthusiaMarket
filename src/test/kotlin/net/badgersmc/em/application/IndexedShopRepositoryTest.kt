package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PERF-2 (REQ-281/282): the IndexedShopRepository decorator keeps the in-memory ShopLocationIndex
 * consistent with every persisted mutation and serves findByContainer from the index without
 * touching the SQL delegate. Red until IndexedShopRepository is implemented.
 */
class IndexedShopRepositoryTest {

    private val owner = UUID.randomUUID()

    private fun shop(id: Long, cx: Int = 10, hopperAllowIn: Boolean = true): Shop = Shop(
        id = id,
        stallId = "stall_01",
        owner = owner,
        signWorld = "world", signX = 0, signY = 64, signZ = 0,
        containerWorld = "world", containerX = cx, containerY = 64, containerZ = 20,
        sellItem = "itemBase64", sellAmount = 1,
        costItem = "costBase64", costAmount = 10,
        hopperAllowIn = hopperAllowIn,
    )

    @Test
    fun `findByContainer reads the index without touching the delegate`() {
        val index = InMemoryShopLocationIndex().apply { put(shop(id = 1)) }
        val delegate = mockk<ShopRepository>(relaxed = true)
        val repo = IndexedShopRepository(delegate, index)

        val result = repo.findByContainer("world", 10, 64, 20)

        assertEquals(listOf(1L), result.map { it.id })
        verify(exactly = 0) { delegate.findByContainer(any(), any(), any(), any()) }
    }

    @Test
    fun `upsert persists via the delegate and indexes the returned shop`() {
        val index = InMemoryShopLocationIndex()
        val delegate = mockk<ShopRepository>(relaxed = true)
        val persisted = shop(id = 5)
        every { delegate.upsert(any()) } returns persisted
        val repo = IndexedShopRepository(delegate, index)

        val out = repo.upsert(shop(id = 0))

        assertEquals(5L, out.id)
        verify { delegate.upsert(any()) }
        assertEquals(listOf(5L), index.shopsAt("world", 10, 64, 20).map { it.id })
    }

    @Test
    fun `upsert of an existing id replaces the indexed entry rather than duplicating`() {
        val v1 = shop(id = 7, hopperAllowIn = true)
        val index = InMemoryShopLocationIndex().apply { put(v1) }
        val v2 = v1.copy(hopperAllowIn = false)
        val delegate = mockk<ShopRepository>(relaxed = true)
        every { delegate.upsert(any()) } returns v2
        val repo = IndexedShopRepository(delegate, index)

        repo.upsert(v2)

        val now = index.shopsAt("world", 10, 64, 20)
        assertEquals(1, now.size, "must not duplicate the same shop id at one coordinate")
        assertFalse(now.single().hopperAllowIn, "index must reflect the updated hopper flag")
    }

    @Test
    fun `delete drops the shop from the index and delegates`() {
        val s = shop(id = 3)
        val index = InMemoryShopLocationIndex().apply { put(s) }
        val delegate = mockk<ShopRepository>(relaxed = true)
        every { delegate.findById(3) } returns s
        val repo = IndexedShopRepository(delegate, index)

        repo.delete(3)

        verify { delegate.delete(3) }
        assertTrue(index.shopsAt("world", 10, 64, 20).isEmpty())
    }

    @Test
    fun `deleteByContainer clears the coordinate and delegates`() {
        val index = InMemoryShopLocationIndex().apply { put(shop(id = 1)) }
        val delegate = mockk<ShopRepository>(relaxed = true)
        val repo = IndexedShopRepository(delegate, index)

        repo.deleteByContainer("world", 10, 64, 20)

        verify { delegate.deleteByContainer("world", 10, 64, 20) }
        assertTrue(index.shopsAt("world", 10, 64, 20).isEmpty())
    }

    @Test
    fun `deleteByOwner removes the owner's shops from the index`() {
        val s = shop(id = 1)
        val index = InMemoryShopLocationIndex().apply { put(s) }
        val delegate = mockk<ShopRepository>(relaxed = true)
        every { delegate.findByOwner(owner) } returns listOf(s)
        every { delegate.deleteByOwner(owner) } returns 1
        val repo = IndexedShopRepository(delegate, index)

        repo.deleteByOwner(owner)

        assertTrue(index.shopsAt("world", 10, 64, 20).isEmpty())
    }

    @Test
    fun `findById delegates to the underlying repository`() {
        val s = shop(id = 9)
        val index = InMemoryShopLocationIndex()
        val delegate = mockk<ShopRepository>(relaxed = true)
        every { delegate.findById(9) } returns s
        val repo = IndexedShopRepository(delegate, index)

        assertEquals(s, repo.findById(9))
        verify { delegate.findById(9) }
    }
}
