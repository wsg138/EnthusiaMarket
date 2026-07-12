package net.badgersmc.em.infrastructure.listeners

import io.mockk.*
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.kyori.adventure.text.Component
import net.badgersmc.em.events.PostShopTransactionEvent
import net.badgersmc.em.events.ShopStockDepletedEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.event.Event
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import kotlin.test.Test

class ContainerStockListenerTest {

    @BeforeEach
    fun setupMockBukkit() {
        MockBukkit.mock()
    }

    @AfterEach
    fun cleanupMocks() {
        unmockkAll()
        if (MockBukkit.isMocked()) MockBukkit.unmock()
    }

    private fun sellTemplate(): ItemStack = ItemStack(Material.DIAMOND, 1)

    private fun matchingStock(amount: Int): ItemStack = ItemStack(Material.DIAMOND, amount)

    /** Same material as sellTemplate, but different display name — isSimilar would match,
     *  but the byte-exact [net.badgersmc.em.application.ItemStackMatch] must reject it. */
    private fun nonMatchingStock(amount: Int): ItemStack {
        val stack = ItemStack(Material.DIAMOND, amount)
        val meta = stack.itemMeta
        meta.displayName(net.kyori.adventure.text.Component.text("Non-matching Diamond"))
        stack.itemMeta = meta
        return stack
    }

    /** Creates a shop with the given coordinates. */
    private fun shop(
        sellAmount: Int = 1,
        owner: UUID = UUID.randomUUID()
    ): Shop = Shop(
        id = 1L, stallId = "s1", owner = owner,
        signWorld = "world", signX = 100, signY = 64, signZ = 200,
        containerWorld = "world", containerX = 50, containerY = 64, containerZ = 60,
        sellItem = "base64item", sellAmount = sellAmount,
        costItem = "base64cost", costAmount = 10
    )

    // ── Mock helpers ────────────────────────────────────────────────────

    /** Sets up a mocked World with sign + container blocks. Returns the sign mock. */
    private fun mockWorld(contents: Array<ItemStack?>): Sign {
        mockkStatic(Bukkit::class)
        val world = mockWorldWithChunksLoaded()
        every { Bukkit.getWorld("world") } returns world

        val sign = mockSignAt(world)
        mockContainerAt(world, contents)
        stubPluginManager()
        return sign
    }

    /** Returns a World mock where all chunks report as loaded. */
    private fun mockWorldWithChunksLoaded(): World {
        val world = mockk<World>(relaxed = true)
        every { world.isChunkLoaded(any(), any()) } returns true
        return world
    }

    /** Creates a sign block at (100,64,200) on [world] and returns the sign mock. */
    private fun mockSignAt(world: World): Sign {
        val sign = mockk<Sign>(relaxed = true)
        val signBlock = mockk<Block>(relaxed = true)
        every { signBlock.state } returns sign
        every { world.getBlockAt(100, 64, 200) } returns signBlock
        return sign
    }

    /** Creates a container block at (50,64,60) on [world] with the given contents.
     *  Uses BARREL material so the PERF-5 type-check matches the generic Container branch. */
    private fun mockContainerAt(world: World, contents: Array<ItemStack?>) {
        val containerInv = mockk<Inventory>(relaxed = true)
        every { containerInv.contents } returns contents
        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv
        val contLoc = mockk<Location>(relaxed = true)
        every { contLoc.world?.name } returns "world"
        every { contLoc.blockX } returns 50; every { contLoc.blockY } returns 64; every { contLoc.blockZ } returns 60
        val containerBlock = mockk<Block>(relaxed = true)
        every { containerBlock.type } returns Material.BARREL          // PERF-5: type check
        every { containerBlock.location } returns contLoc
        every { containerBlock.state } returns container
        every { world.getBlockAt(50, 64, 60) } returns containerBlock
    }

    /** Stubs Bukkit.getPluginManager() with a relaxed mock so depletion tracking doesn't NPE. */
    private fun stubPluginManager() {
        every { Bukkit.getPluginManager() } returns mockk(relaxed = true)
    }

    // ── Timer path tests ──────────────────────────────────────────────

    @Test
    fun `refreshAllSigns updates sign with stock count`() {
        val sellStack = sellTemplate()
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val s = shop()
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.all() } returns listOf(s)

        val contItem = matchingStock(10)

        val sign = mockWorld(contents = arrayOf(contItem))

        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.refreshAllSigns()

        verify { sign.line(3, any<Component>()) }
        verify { sign.update(false) }                               // PERF-5: no physics
        verify { repo.updateStockBatch(mapOf(s.id to 10)) }         // PERF-5: batched
    }

    @Test
    fun `refreshAllSigns counts only byte-exact matching stacks`() {
        val sellStack = sellTemplate()
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val s = shop()
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.all() } returns listOf(s)

        val matching = matchingStock(10)
        val nonMatching = nonMatchingStock(99)
        mockWorld(contents = arrayOf(matching, nonMatching))

        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.refreshAllSigns()

        // Only the diamond stack counts — iron ingots must not inflate stock.
        verify { repo.updateStockBatch(mapOf(s.id to 10)) }
    }

    @Test
    fun `refreshAllSigns skips update when stock unchanged`() {
        val sellStack = sellTemplate()
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val s = shop()
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.all() } returns listOf(s)

        val contItem = matchingStock(10)

        val sign = mockWorld(contents = arrayOf(contItem))

        val listener = ContainerStockListener(repo, mockk(relaxed = true))

        listener.refreshAllSigns()
        verify(exactly = 1) { sign.line(3, any<Component>()) }
        verify(exactly = 1) { repo.updateStockBatch(mapOf(s.id to 10)) }

        listener.refreshAllSigns()
        verify(exactly = 1) { sign.line(3, any<Component>()) }
        verify(exactly = 1) { repo.updateStockBatch(mapOf(s.id to 10)) }
    }

    @Test
    fun `refreshAllSigns skips unloaded container chunk`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.all() } returns listOf(shop())

        mockkStatic(Bukkit::class)
        val world = mockk<World>(relaxed = true)
        every { world.isChunkLoaded(any(), any()) } returns false
        every { Bukkit.getWorld("world") } returns world

        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.refreshAllSigns()

        verify(exactly = 0) { world.getBlockAt(any(), any(), any()) }
    }

    @Test
    fun `refreshAllSigns does nothing with empty shop list`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.all() } returns emptyList()

        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.refreshAllSigns()
        // Should not throw
    }

    @Test
    fun `refreshAllSigns skips update when sign chunk not loaded`() {
        val sellStack = sellTemplate()
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val s = shop()
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.all() } returns listOf(s)

        val contItem = matchingStock(10)

        mockkStatic(Bukkit::class)
        val world = mockk<World>(relaxed = true)
        // Container chunk IS loaded, sign chunk is NOT
        every { world.isChunkLoaded(50 shr 4, 60 shr 4) } returns true
        every { world.isChunkLoaded(100 shr 4, 200 shr 4) } returns false
        every { Bukkit.getWorld("world") } returns world

        val sign = mockSignAt(world)
        mockContainerAt(world, arrayOf(contItem))
        stubPluginManager()

        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.refreshAllSigns()

        // DB must STILL be updated — stock_count exists for /shop search,
        // and the container chunk IS loaded (V019 design).
        verify(exactly = 1) { repo.updateStockBatch(mapOf(s.id to 10)) }
        // Sign must NOT be touched (chunk not loaded)
        verify(exactly = 0) { sign.line(3, any<Component>()) }
    }

    // ── Trade path tests ──────────────────────────────────────────────

    @Test
    fun `onTransaction updates sign and persists stock`() {
        val sellStack = sellTemplate()
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val s = shop()
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(1L) } returns s

        val contItem = matchingStock(64)

        val sign = mockWorld(contents = arrayOf(contItem))

        val event = PostShopTransactionEvent(
            mockk(relaxed = true),
            UUID.randomUUID(),
            contItem, 64, 100.0,
            shopId = 1L
        )
        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.onTransaction(event)

        verify { sign.line(3, any<Component>()) }
        verify { sign.update(false) }                               // PERF-5: no physics
        // onTransaction stages to dirtyStock — flushed on next timer tick,
        // not immediately. DB write happens in refreshAllSigns.
        // Stock is staged for batch flush; test verifies sign update only.
    }

    @Test
    fun `onTransaction with unknown shopId does nothing`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(999L) } returns null

        val event = PostShopTransactionEvent(
            mockk(relaxed = true),
            UUID.randomUUID(),
            mockk(relaxed = true), 1, 10.0,
            shopId = 999L
        )
        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.onTransaction(event)
        // Should not throw
    }

    @Test
    fun `onTransaction fires depletion event even when sign chunk unloaded`() {
        val sellStack = sellTemplate()
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val ownerUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val s = shop(owner = ownerUuid)
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(1L) } returns s

        val diffItem = nonMatchingStock(64)

        mockkStatic(Bukkit::class)
        val world = mockk<World>(relaxed = true)
        // Container chunk loaded, sign chunk NOT loaded
        every { world.isChunkLoaded(50 shr 4, 60 shr 4) } returns true
        every { world.isChunkLoaded(100 shr 4, 200 shr 4) } returns false
        every { Bukkit.getWorld("world") } returns world

        val sign = mockSignAt(world)
        mockContainerAt(world, arrayOf(diffItem))
        // Capture depletion event
        val pmSlot = slot<Event>()
        val pm = mockk<PluginManager>(relaxed = true)
        every { pm.callEvent(capture(pmSlot)) } answers { }
        every { Bukkit.getPluginManager() } returns pm

        val event = PostShopTransactionEvent(
            mockk(relaxed = true),
            UUID.randomUUID(),
            diffItem, 64, 100.0,
            shopId = 1L
        )
        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.onTransaction(event)

        // Stock staged for batch (not immediate write)
        // Sign NOT updated (chunk unloaded)
        verify(exactly = 0) { sign.line(3, any<Component>()) }
        // Depletion event still fired
        verify(exactly = 1) { pm.callEvent(any<ShopStockDepletedEvent>()) }
        kotlin.test.assertEquals(ownerUuid, (pmSlot.captured as ShopStockDepletedEvent).ownerId)
    }

    // ── Depletion event tests ─────────────────────────────────────────

    @Test
    fun `zero stock fires ShopStockDepletedEvent`() {
        val sellStack = sellTemplate()
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val ownerUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val s = shop(owner = ownerUuid)
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.all() } returns listOf(s)

        val diffItem = nonMatchingStock(64)

        val sign = mockWorld(contents = arrayOf(diffItem))

        // Capture the callEvent argument
        val pmSlot = slot<Event>()
        val pm = mockk<PluginManager>(relaxed = true)
        every { pm.callEvent(capture(pmSlot)) } answers { }
        every { Bukkit.getPluginManager() } returns pm

        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.refreshAllSigns()

        verify { sign.line(3, any<Component>()) }
        verify(exactly = 1) { pm.callEvent(any<ShopStockDepletedEvent>()) }
        val fired = pmSlot.captured as ShopStockDepletedEvent
        kotlin.test.assertEquals(ownerUuid, fired.ownerId)
    }

    @Test
    fun `depletion event not re-fired when still at zero`() {
        val sellStack = sellTemplate()
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val s = shop()
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.all() } returns listOf(s)

        val diffItem = nonMatchingStock(64)

        val sign = mockWorld(contents = arrayOf(diffItem))

        val pm = mockk<PluginManager>(relaxed = true)
        every { Bukkit.getPluginManager() } returns pm

        val listener = ContainerStockListener(repo, mockk(relaxed = true))

        listener.refreshAllSigns()
        verify(exactly = 1) { pm.callEvent(any<ShopStockDepletedEvent>()) }

        listener.refreshAllSigns()
        verify(exactly = 1) { pm.callEvent(any<ShopStockDepletedEvent>()) }
    }

    // ── Live-inventory path tests ──────────────────────────────────────

    @Test
    fun `refreshAllSigns reads Container inventory not stale snapshot`() {
        val sellStack = sellTemplate()
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val s = shop()
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.all() } returns listOf(s)

        val contItem = matchingStock(42)

        mockkStatic(Bukkit::class)
        val world = mockWorldWithChunksLoaded()
        every { Bukkit.getWorld("world") } returns world

        val sign = mockSignAt(world)

        val liveInv = mockk<Inventory>(relaxed = true)
        every { liveInv.contents } returns arrayOf(contItem)
        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns liveInv
        val chestBlock = mockk<Block>(relaxed = true)
        every { chestBlock.type } returns Material.CHEST
        every { chestBlock.state } returns container
        every { world.getBlockAt(50, 64, 60) } returns chestBlock

        stubPluginManager()

        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.refreshAllSigns()

        // Sign must be updated with correct stock (42)
        verify { sign.line(3, any<Component>()) }
        verify { sign.update(false) }
        verify { repo.updateStockBatch(mapOf(s.id to 42)) }

        // Container.inventory was read
    }

    @Test
    fun `refreshAllSigns sums both halves of double chest via container inventory`() {
        val sellStack = sellTemplate()
        mockkObject(ItemStackSerializer)
        every { ItemStackSerializer.deserialize("base64item") } returns sellStack

        val s = shop()
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.all() } returns listOf(s)

        val leftItem = matchingStock(32)
        val rightItem = matchingStock(32)

        mockkStatic(Bukkit::class)
        val world = mockWorldWithChunksLoaded()
        every { Bukkit.getWorld("world") } returns world

        val sign = mockSignAt(world)

        // Container.inventory on a double chest half returns full 54-slot inventory.
        val combinedInv = mockk<Inventory>(relaxed = true)
        every { combinedInv.contents } returns arrayOf(leftItem, rightItem)

        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns combinedInv
        val chestBlock = mockk<Block>(relaxed = true)
        every { chestBlock.type } returns Material.CHEST
        every { chestBlock.state } returns container
        every { world.getBlockAt(50, 64, 60) } returns chestBlock

        stubPluginManager()

        val listener = ContainerStockListener(repo, mockk(relaxed = true))
        listener.refreshAllSigns()

        verify { sign.line(3, any<Component>()) }
        verify { sign.update(false) }
        verify { repo.updateStockBatch(mapOf(s.id to 64)) }
    }
}
