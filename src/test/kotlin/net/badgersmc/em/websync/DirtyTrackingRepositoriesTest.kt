package net.badgersmc.em.websync

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirtyTrackingRepositoriesTest {
    private val owner = UUID.fromString("00000000-0000-4000-8000-000000000001")

    @Test
    fun `stall writes delegate first and dirty failures never escape`() {
        val delegate = mockk<StallRepository>()
        val dirty = mockk<WebsiteSyncDirtySink>()
        val stall = stall("stall1")
        every { delegate.save(stall) } just runs
        every { dirty.markDirty("stall1") } throws IllegalStateException("isolated")
        DirtyTrackingStallRepository(delegate, dirty).save(stall)
        verifyOrder { delegate.save(stall); dirty.markDirty("stall1") }
    }

    @Test
    fun `stall create notifies only after a successful delegate mutation`() {
        val delegate = mockk<StallRepository>()
        val ids = mutableListOf<String>()
        val value = stall("stall1")
        every { delegate.create(value) } just runs
        val repository = DirtyTrackingStallRepository(delegate, WebsiteSyncDirtySink(ids::add))
        repository.create(value)
        assertEquals(listOf("stall1"), ids)
        every { delegate.create(value) } throws IllegalStateException("database")
        ids.clear()
        assertFailsWith<IllegalStateException> { repository.create(value) }
        assertEquals(emptyList(), ids)
    }

    @Test
    fun `upsert dirties old and new stall associations after persistence`() {
        val old = shop(1, "stall1")
        val moved = old.copy(stallId = "stall2")
        val delegate = mockk<ShopRepository>(relaxed = true)
        val ids = mutableListOf<String>()
        every { delegate.all() } returns listOf(old)
        every { delegate.upsert(moved) } returns moved
        val repository = DirtyTrackingShopRepository(delegate, WebsiteSyncDirtySink(ids::add))
        assertEquals(moved, repository.upsert(moved))
        assertEquals(listOf("stall1", "stall2"), ids)
        verify { delegate.upsert(moved) }
    }

    @Test
    fun `destructive and stock mutations capture exact affected stalls`() {
        val first = shop(1, "stall1")
        val second = shop(2, "stall2")
        val delegate = mockk<ShopRepository>(relaxed = true)
        val ids = mutableListOf<String>()
        every { delegate.all() } returns listOf(first, second)
        every { delegate.findByOwner(owner) } returns listOf(first, second)
        every { delegate.deleteByOwner(owner) } returns 2
        val repository = DirtyTrackingShopRepository(delegate, WebsiteSyncDirtySink(ids::add))
        repository.updateStockBatch(mapOf(1L to 4, 2L to 5))
        repository.deleteByOwner(owner)
        repository.freezeByStall("stall3", true)
        assertEquals(listOf("stall1", "stall2", "stall1", "stall2", "stall3"), ids)
    }

    @Test
    fun `every shop mutation marks exact coalesced stall IDs after delegation`() {
        val first = shop(1, "stall1")
        val duplicate = shop(2, "stall1")
        val third = shop(3, "stall2")
        val delegate = mockk<ShopRepository>(relaxed = true)
        val ids = mutableListOf<String>()
        every { delegate.all() } returns listOf(first, duplicate, third)
        every { delegate.findByContainer("world", 1, 2, 3) } returns listOf(first, duplicate, third)
        every { delegate.findByOwner(owner) } returns listOf(first, duplicate, third)
        every { delegate.findById(1) } returns first
        every { delegate.findById(2) } returns duplicate
        every { delegate.findById(3) } returns third
        every { delegate.deleteByOwner(owner) } returns 3
        val repository = DirtyTrackingShopRepository(delegate, WebsiteSyncDirtySink(ids::add))

        repository.delete(1)
        assertEquals(listOf("stall1"), ids)
        ids.clear()
        repository.deleteByContainer("world", 1, 2, 3)
        assertEquals(listOf("stall1", "stall2"), ids)
        ids.clear()
        repository.deleteByOwner(owner)
        assertEquals(listOf("stall1", "stall2"), ids)
        ids.clear()
        repository.updateStock(3, 5)
        assertEquals(listOf("stall2"), ids)
        ids.clear()
        repository.updateStockBatch(mapOf(1L to 1, 2L to 2, 3L to 3))
        assertEquals(listOf("stall1", "stall2"), ids)
        ids.clear()
        repository.freezeByStall("stall3", true)
        assertEquals(listOf("stall3"), ids)
    }

    @Test
    fun `failed mutation does not notify and lookup failures are safely observable`() {
        val delegate = mockk<ShopRepository>(relaxed = true)
        val ids = mutableListOf<String>()
        val failures = mutableListOf<String>()
        every { delegate.all() } returns emptyList()
        every { delegate.findById(1) } throws IllegalStateException("private detail")
        every { delegate.updateStock(1, 2) } throws IllegalStateException("database")
        val repository = DirtyTrackingShopRepository(
            delegate,
            WebsiteSyncDirtySink(ids::add),
            DirtyTrackingFailureObserver { operation, stallId -> failures += "$operation:${stallId.orEmpty()}" },
        )
        assertFailsWith<IllegalStateException> { repository.updateStock(1, 2) }
        assertEquals(emptyList(), ids)
        assertEquals(listOf("lookup_shop:"), failures)
    }

    @Test
    fun `dirty sink failures never fail a successful shop mutation`() {
        val value = shop(1, "stall1")
        val delegate = mockk<ShopRepository>(relaxed = true)
        every { delegate.all() } returns listOf(value)
        val repository = DirtyTrackingShopRepository(delegate, WebsiteSyncDirtySink { error("sink") })
        repository.updateStock(1, 2)
        verify { delegate.updateStock(1, 2) }
    }

    private fun stall(id: String) = Stall(
        StallId(id), id, "world", StallState.OWNED, OwnerRef.solo(owner), null, 1, RentTerms.flat(1),
    )

    private fun shop(id: Long, stallId: String) = Shop(
        id, stallId, owner, "world", 0, 64, 0, "world", 0, 64, 1,
        "item", 1, "cost", 1,
    )
}
