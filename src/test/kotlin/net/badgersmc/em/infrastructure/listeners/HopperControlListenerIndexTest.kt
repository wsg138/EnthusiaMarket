package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.application.IndexedShopRepository
import net.badgersmc.em.application.InMemoryShopLocationIndex
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import org.bukkit.Location
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PERF-3 (merged into PERF-4), REQ-281: when the listener is given the IndexedShopRepository,
 * the hopper hot path resolves shop status from the in-memory index and never queries the SQL
 * delegate's findByContainer on the server thread. The delegate spy fails the test if its
 * findByContainer/queryMany is ever touched.
 */
class HopperControlListenerIndexTest {

    private fun shopAt(cx: Int, hopperAllowOut: Boolean = true, hopperAllowIn: Boolean = true) = Shop(
        id = 1L, stallId = "s1", owner = UUID.randomUUID(),
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = cx, containerY = 64, containerZ = 20,
        sellItem = "a", sellAmount = 1, costItem = "b", costAmount = 1,
        hopperAllowOut = hopperAllowOut, hopperAllowIn = hopperAllowIn,
    )

    /** A ShopRepository delegate whose hot-path read fails the test if invoked. */
    private fun throwingDelegate(): ShopRepository = mockk(relaxed = true) {
        every { findByContainer(any(), any(), any(), any()) } answers
            { error("findByContainer must not hit the DB delegate on the hopper hot path") }
    }

    private fun containerInv(cx: Int): Inventory {
        val loc = mockk<Location>(relaxed = true)
        every { loc.world?.name } returns "world"
        every { loc.blockX } returns cx
        every { loc.blockY } returns 64
        every { loc.blockZ } returns 20
        val inv = mockk<Inventory>(relaxed = true)
        every { inv.location } returns loc
        return inv
    }

    @Test
    fun `extract from hopperAllowOut-false shop is cancelled using the index, not the DB`() {
        val index = InMemoryShopLocationIndex().apply { put(shopAt(10, hopperAllowOut = false)) }
        val delegate = throwingDelegate()
        val repo = IndexedShopRepository(delegate, index)
        val destInv = mockk<Inventory>(relaxed = true)
        every { destInv.holder } returns mockk<org.bukkit.entity.HumanEntity>(relaxed = true)

        val event = InventoryMoveItemEvent(containerInv(10), mockk<ItemStack>(relaxed = true), destInv, true)
        HopperControlListener(repo).onHopperMove(event)

        assertTrue(event.isCancelled)
        verify(exactly = 0) { delegate.findByContainer(any(), any(), any(), any()) }
    }

    @Test
    fun `non-shop container is untouched and never queries the DB`() {
        val index = InMemoryShopLocationIndex() // empty: no shops anywhere
        val delegate = throwingDelegate()
        val repo = IndexedShopRepository(delegate, index)

        val event = InventoryMoveItemEvent(containerInv(99), mockk<ItemStack>(relaxed = true), containerInv(98), true)
        HopperControlListener(repo).onHopperMove(event)

        assertTrue(!event.isCancelled)
        verify(exactly = 0) { delegate.findByContainer(any(), any(), any(), any()) }
    }
}
