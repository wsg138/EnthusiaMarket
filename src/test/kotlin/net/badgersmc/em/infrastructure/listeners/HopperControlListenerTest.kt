package net.badgersmc.em.infrastructure.listeners

import io.mockk.*
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import org.bukkit.Location
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.test.Test

class HopperControlListenerTest {

    @Test
    fun `hopper extracts from container with hopperAllowOut false is cancelled`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val shop = Shop(
            id = 1L, stallId = "s1", owner = UUID.randomUUID(),
            signWorld = "w", signX = 1, signY = 2, signZ = 3,
            containerWorld = "w", containerX = 10, containerY = 64, containerZ = 20,
            sellItem = "a", sellAmount = 1, costItem = "b", costAmount = 1,
            hopperAllowOut = false, hopperAllowIn = true
        )

        val loc = mockk<Location>(relaxed = true)
        every { loc.world?.name } returns "world"
        every { loc.blockX } returns 10
        every { loc.blockY } returns 64
        every { loc.blockZ } returns 20

        val sourceInv = mockk<Inventory>(relaxed = true)
        every { sourceInv.location } returns loc
        val destInv = mockk<Inventory>(relaxed = true)
        // Non-container destination: location is null → fallback holder check
        every { destInv.holder } returns mockk<org.bukkit.entity.HumanEntity>(relaxed = true)

        every { repo.findByContainer("world", 10, 64, 20) } returns listOf(shop)

        val event = InventoryMoveItemEvent(sourceInv, mockk<ItemStack>(relaxed = true), destInv, true)
        val listener = HopperControlListener(repo)

        listener.onHopperMove(event)

        assert(event.isCancelled) { "Hopper extract should be cancelled when hopperAllowOut=false" }
    }

    @Test
    fun `hopper inserts into container with hopperAllowIn false is cancelled`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val shop = Shop(
            id = 1L, stallId = "s1", owner = UUID.randomUUID(),
            signWorld = "w", signX = 1, signY = 2, signZ = 3,
            containerWorld = "w", containerX = 10, containerY = 64, containerZ = 20,
            sellItem = "a", sellAmount = 1, costItem = "b", costAmount = 1,
            hopperAllowIn = false, hopperAllowOut = true
        )

        val loc = mockk<Location>(relaxed = true)
        every { loc.world?.name } returns "world"
        every { loc.blockX } returns 10
        every { loc.blockY } returns 64
        every { loc.blockZ } returns 20

        val sourceInv = mockk<Inventory>(relaxed = true)
        // Hopper source: location is null → fallback holder check
        every { sourceInv.holder } returns mockk<org.bukkit.block.Hopper>(relaxed = true)
        val destInv = mockk<Inventory>(relaxed = true)
        every { destInv.location } returns loc

        every { repo.findByContainer("world", 10, 64, 20) } returns listOf(shop)

        val event = InventoryMoveItemEvent(sourceInv, mockk<ItemStack>(relaxed = true), destInv, true)
        val listener = HopperControlListener(repo)

        listener.onHopperMove(event)

        assert(event.isCancelled) { "Hopper insert should be cancelled when hopperAllowIn=false" }
    }

    @Test
    fun `hopper operates normally on non-shop container`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findByContainer(any(), any(), any(), any()) } returns emptyList()

        val sourceInv = mockk<Inventory>(relaxed = true)
        every { sourceInv.location } returns mockk<Location>(relaxed = true)
        val destInv = mockk<Inventory>(relaxed = true)
        every { destInv.location } returns mockk<Location>(relaxed = true)

        val event = InventoryMoveItemEvent(sourceInv, mockk<ItemStack>(relaxed = true), destInv, true)
        val listener = HopperControlListener(repo)

        listener.onHopperMove(event)

        assert(!event.isCancelled) { "Non-shop hopper should not be cancelled" }
    }
}
