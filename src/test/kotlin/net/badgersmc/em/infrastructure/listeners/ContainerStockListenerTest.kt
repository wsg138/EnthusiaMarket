package net.badgersmc.em.infrastructure.listeners

import io.mockk.*
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.kyori.adventure.text.Component
import net.badgersmc.em.events.ShopStockDepletedEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.inventory.*
import org.mockbukkit.mockbukkit.MockBukkit
import org.junit.jupiter.api.AfterEach
import java.util.UUID
import kotlin.test.Test

class ContainerStockListenerTest {

    @AfterEach
    fun cleanupMocks() {
        // The helper methods in this class call mockkStatic(Bukkit::class) without
        // an unmockkStatic — and mockk's static mocks are JVM-global. Without this
        // teardown, the static mock leaks into the next test class's execution and
        // breaks anything that relies on Bukkit.getPluginManager() being dispatched
        // through MockBukkit's ServerMock (e.g. ShopDeletedEventTest's
        // assertEventFired never sees fired events because callEvent is intercepted
        // by the leaked relaxed mock instead).
        unmockkAll()
        if (MockBukkit.isMocked()) MockBukkit.unmock()
    }

    /** Creates a shop with the given coordinates. */
    private fun shop(
        signX: Int = 100, signY: Int = 64, signZ: Int = 200,
        contX: Int = 50, contY: Int = 64, contZ: Int = 60,
        sellAmount: Int = 1,
        owner: UUID = UUID.randomUUID()
    ): Shop = Shop(
        id = 1L, stallId = "s1", owner = owner,
        signWorld = "world", signX = signX, signY = signY, signZ = signZ,
        containerWorld = "world", containerX = contX, containerY = contY, containerZ = contZ,
        sellItem = "base64item", sellAmount = sellAmount,
        costItem = "base64cost", costAmount = 10
    )

    /**
     * Sets up Bukkit.getWorld("world") and registers blocks at:
     *  - (signX, signY, signZ) → sign mock
     *  - (contX, contY, contZ) → container with the given inventory contents
     * Returns the [Sign] mock so tests can verify interactions.
     */
    private fun mockWorldWithShop(
        signX: Int = 100, signY: Int = 64, signZ: Int = 200,
        contX: Int = 50, contY: Int = 64, contZ: Int = 60,
        contents: Array<ItemStack?> = emptyArray()
    ): Sign {
        mockkStatic(org.bukkit.Bukkit::class)
        val world = mockk<World>(relaxed = true)
        every { org.bukkit.Bukkit.getWorld("world") } returns world

        // Sign block
        val sign = mockk<Sign>(relaxed = true)
        val signBlock = mockk<Block>(relaxed = true).also {
            every { it.state } returns sign
        }
        every { world.getBlockAt(signX, signY, signZ) } returns signBlock

        // Container block at shop's container coords
        val contLoc = mockk<Location>(relaxed = true).also {
            every { it.world?.name } returns "world"
            every { it.blockX } returns contX
            every { it.blockY } returns contY
            every { it.blockZ } returns contZ
        }
        val containerInv = mockk<Inventory>(relaxed = true).also {
            every { it.contents } returns contents
        }
        val container = mockk<Container>(relaxed = true).also {
            every { it.inventory } returns containerInv
        }
        val containerBlock = mockk<Block>(relaxed = true).also {
            every { it.location } returns contLoc
            every { it.state } returns container
        }
        every { world.getBlockAt(contX, contY, contZ) } returns containerBlock

        return sign
    }

    /** Creates a mock InventoryView whose top inventory holder is the given object. */
    private fun inventoryView(holder: Any): InventoryView {
        val topInv = mockk<Inventory>(relaxed = true).also {
            every { it.holder } returns (holder as? org.bukkit.inventory.InventoryHolder)
        }
        return mockk<InventoryView>(relaxed = true).also {
            every { it.topInventory } returns topInv
        }
    }

    /** Creates a mock Container holder for the inventory view. */
    private fun containerHolder(): org.bukkit.inventory.InventoryHolder {
        val contLoc = mockk<Location>(relaxed = true).also {
            every { it.world?.name } returns "world"
            every { it.blockX } returns 50
            every { it.blockY } returns 64
            every { it.blockZ } returns 60
        }
        val container = mockk<Container>(relaxed = true)
        val block = mockk<Block>(relaxed = true).also {
            every { it.location } returns contLoc
            every { it.state } returns container
        }
        every { container.block } returns block
        return container
    }

    @Test
    fun `click in linked container refreshes sign with stock count`() {
        // Mock deserialize
        val sellStack = mockk<ItemStack>(relaxed = true)
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val shop = shop()
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findByContainer("world", 50, 64, 60) } returns listOf(shop)

        // Container has 10 items matching the sell item
        val contItem = mockk<ItemStack>(relaxed = true)
        every { contItem.isSimilar(sellStack) } returns true
        every { contItem.amount } returns 10

        // World has sign at (100,64,200) and container at (50,64,60) with 10 items
        val sign = mockWorldWithShop(contents = arrayOf(contItem))

        // The view's top inventory is our container
        val view = inventoryView(containerHolder())

        val event = InventoryClickEvent(
            view, InventoryType.SlotType.CONTAINER, 0,
            ClickType.LEFT, InventoryAction.PICKUP_ALL
        )
        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.onClick(event)

        verify { sign.line(3, any<Component>()) }
        verify { sign.update(true) }
    }

    @Test
    fun `click in non-shop container does nothing`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findByContainer(any(), any(), any(), any()) } returns emptyList()

        val view = inventoryView(containerHolder())

        val event = InventoryClickEvent(
            view, InventoryType.SlotType.CONTAINER, 0,
            ClickType.LEFT, InventoryAction.PICKUP_ALL
        )
        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.onClick(event)
        // Should not throw
    }

    @Test
    fun `drag in linked container refreshes sign with stock count`() {
        val sellStack = mockk<ItemStack>(relaxed = true)
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val shop = shop()
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findByContainer("world", 50, 64, 60) } returns listOf(shop)

        val contItem = mockk<ItemStack>(relaxed = true)
        every { contItem.isSimilar(sellStack) } returns true
        every { contItem.amount } returns 10

        val sign = mockWorldWithShop(contents = arrayOf(contItem))
        val view = inventoryView(containerHolder())

        val event = InventoryDragEvent(
            view,
            mockk<ItemStack>(relaxed = true),
            mockk<ItemStack>(relaxed = true),
            false,
            mapOf(0 to mockk<ItemStack>(relaxed = true))
        )
        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.onDrag(event)

        verify { sign.line(3, any<Component>()) }
        verify { sign.update(true) }
    }

    @Test
    fun `drag in non-shop container does nothing`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findByContainer(any(), any(), any(), any()) } returns emptyList()

        val view = inventoryView(containerHolder())

        val event = InventoryDragEvent(
            view,
            mockk<ItemStack>(relaxed = true),
            mockk<ItemStack>(relaxed = true),
            false,
            mapOf(0 to mockk<ItemStack>(relaxed = true))
        )
        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.onDrag(event)
        // Should not throw
    }

    @Test
    fun `click in player inventory does nothing`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val player = mockk<org.bukkit.entity.Player>(relaxed = true)
        val view = inventoryView(player)

        val event = InventoryClickEvent(
            view, InventoryType.SlotType.CONTAINER, 0,
            ClickType.LEFT, InventoryAction.PICKUP_ALL
        )
        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.onClick(event)
        // Should not call findByContainer
        verify(exactly = 0) { repo.findByContainer(any(), any(), any(), any()) }
    }

    @Test
    fun `click in container with deserialize failure shows stock 0`() {
        // deserialize returns null -> 0 trades
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns null

        val shop = shop()
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findByContainer("world", 50, 64, 60) } returns listOf(shop)

        // Container contents don't matter since deserialize returns null
        val sign = mockWorldWithShop()
        every { Bukkit.getPluginManager() } returns mockk(relaxed = true)
        val view = inventoryView(containerHolder())

        val event = InventoryClickEvent(
            view, InventoryType.SlotType.CONTAINER, 0,
            ClickType.LEFT, InventoryAction.PICKUP_ALL
        )
        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.onClick(event)

        verify { sign.line(3, any<Component>()) }
        verify { sign.update(true) }
    }

    @Test
    fun `inventory change to zero stock fires ShopStockDepletedEvent with correct owner UUID`() {
        // ── Given: a shop with zero stock in its linked container ──
        val server = MockBukkit.mock()
        try {
            val sellStack = mockk<ItemStack>(relaxed = true)
            mockkObject(ItemStackSerializer)
            every { ItemStackSerializer.deserialize("base64item") } returns sellStack

            val ownerUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            val shop = shop(owner = ownerUuid)
            val repo = mockk<ShopRepository>(relaxed = true)
            every { repo.findByContainer("world", 50, 64, 60) } returns listOf(shop)

            // Non-matching item → stock = 0
            val diffItem = mockk<ItemStack>(relaxed = true)
            every { diffItem.isSimilar(sellStack) } returns false

            val sign = mockWorldWithShop(contents = arrayOf(diffItem))

            // Point Bukkit.getPluginManager() at server's plugin manager
            every { org.bukkit.Bukkit.getPluginManager() } returns server.pluginManager

            val view = inventoryView(containerHolder())
            val event = InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PICKUP_ALL
            )
            val listener = ContainerStockListener(repo, mockk(relaxed = true))

            // ── When: the listener processes the inventory click ──
            listener.onClick(event)

            // ── Then: ShopStockDepletedEvent should have been fired ──
            server.pluginManager.assertEventFired(ShopStockDepletedEvent::class.java) { e ->
                e.ownerId == ownerUuid
            }
        } finally {
            MockBukkit.unmock()
        }
    }
}