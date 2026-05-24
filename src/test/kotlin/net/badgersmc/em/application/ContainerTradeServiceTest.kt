package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.events.PostShopTransactionEvent
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertTrue

class ContainerTradeServiceTest {

    private val playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val ownerUuid = UUID.fromString("00000000-0000-0000-0000-000000000002")

    /** Sample shop for most tests. */
    private fun testShop(
        stallId: String = "stall_01",
        frozen: Boolean = false,
        sellAmount: Int = 1,
        costAmount: Int = 10
    ): Shop = Shop(
        stallId = stallId,
        owner = ownerUuid,
        signWorld = "world", signX = 100, signY = 64, signZ = 200,
        containerWorld = "world", containerX = 0, containerY = 0, containerZ = 0,
        sellItem = "itemBase64", sellAmount = sellAmount,
        costItem = "costBase64", costAmount = costAmount,
        frozen = frozen
    )

    /** Sample owned stall. */
    private fun sampleStall(owner: UUID = ownerUuid): Stall = Stall(
        id = StallId("stall_01"),
        regionId = "stall_01",
        world = "world",
        state = StallState.OWNED,
        owner = OwnerRef.solo(owner),
        ownerSince = java.time.Instant.now(),
        winningBid = 1000L,
        rentTerms = RentTerms.formula(0.01)
    )

    /**
     * Build a [ContainerTradeService] with overridden [getContainer] and [deserializeStack]
     * so tests don't need a real Bukkit runtime or base64 ItemStack serialization.
     */
    private fun buildService(
        stallRepo: StallRepository = mockk(relaxed = true),
        economy: EconomyProvider = mockk(relaxed = true),
        mockItemStack: ItemStack = mockk(relaxed = true),
        mockContainer: Container = mockk(relaxed = true)
    ): ContainerTradeService {
        return object : ContainerTradeService(stallRepo, economy, mockk<Logger>(relaxed = true)) {
            override fun deserializeStack(base64: String): ItemStack? = mockItemStack
            override fun getContainer(shop: Shop): Container? = mockContainer
        }
    }

    /// ===== Frozen shop =====

    @Test
    fun `executeBuy fails when shop is frozen`() {
        val shop = testShop(frozen = true)
        val service = buildService()
        val result = service.executeBuy(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure)
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("frozen", ignoreCase = true))
    }

    @Test
    fun `executeSell fails when shop is frozen`() {
        val shop = testShop(frozen = true)
        val service = buildService()
        val result = service.executeSell(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure)
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("frozen", ignoreCase = true))
    }

    // ===== Stall not found =====

    @Test
    fun `executeBuy fails when stall not found`() {
        val shop = testShop(stallId = "nonexistent")
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("nonexistent")) } returns null
        val service = buildService(stallRepo = stallRepo)
        val result = service.executeBuy(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure)
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("Stall not found", ignoreCase = true))
    }

    @Test
    fun `executeSell fails when stall not found`() {
        val shop = testShop(stallId = "nonexistent")
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("nonexistent")) } returns null
        val service = buildService(stallRepo = stallRepo)
        val result = service.executeSell(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure)
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("Stall not found", ignoreCase = true))
    }

    // ===== Player not online =====

    @Test
    fun `executeBuy fails when player not online`() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns null

        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()
        val service = buildService(stallRepo = stallRepo)

        val result = service.executeBuy(testShop(), playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure for player not online")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("not online", ignoreCase = true))
    }

    @Test
    fun `executeSell fails when player not online`() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns null

        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()
        val service = buildService(stallRepo = stallRepo)

        val result = service.executeSell(testShop(), playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure for player not online")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("not online", ignoreCase = true))
    }

    // ===== Container missing =====

    @Test
    fun `executeBuy fails when container missing`() {
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val economy = mockk<EconomyProvider>(relaxed = true)
        every { economy.balance(any()) } returns 100L

        mockkStatic(Bukkit::class)
        val playerInv = mockk<PlayerInventory>(relaxed = true)
        every { playerInv.containsAtLeast(any<ItemStack>(), any()) } returns true
        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv
        every { Bukkit.getPlayer(playerUuid) } returns player

        val service = object : ContainerTradeService(stallRepo, economy, mockk<Logger>(relaxed = true)) {
            override fun deserializeStack(base64: String): ItemStack? = mockk(relaxed = true)
            override fun getContainer(shop: Shop): Container? = null
        }

        val result = service.executeBuy(testShop(), playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure for missing container")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("Container missing", ignoreCase = true))
    }

    @Test
    fun `executeSell fails when container missing`() {
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns mockk(relaxed = true)

        val service = object : ContainerTradeService(stallRepo, mockk(relaxed = true), mockk<Logger>(relaxed = true)) {
            override fun deserializeStack(base64: String): ItemStack? = mockk(relaxed = true)
            override fun getContainer(shop: Shop): Container? = null
        }

        val result = service.executeSell(testShop(), playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure for missing container")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("Container missing", ignoreCase = true))
    }

    // ===== BUY: Success =====

    @Test
    fun `executeBuy succeeds when player has item and container has space`() {
        val shop = testShop(costAmount = 50)

        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val economy = mockk<EconomyProvider>(relaxed = true)
        every { economy.balance(ownerUuid) } returns 100L
        every { economy.withdraw(ownerUuid, 50L) } returns true
        every { economy.deposit(playerUuid, 50L) } returns true

        val playerInv = mockk<PlayerInventory>(relaxed = true)
        every { playerInv.containsAtLeast(any<ItemStack>(), any()) } returns true

        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player
        every { Bukkit.getPluginManager() } returns mockk(relaxed = true)

        val containerInv = mockk<Inventory>(relaxed = true)
        // addItem returns HashMap<Int, ItemStack>, empty map = success
        every { containerInv.addItem(any()) } returns hashMapOf()

        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv

        val service = buildService(
            stallRepo = stallRepo,
            economy = economy,
            mockContainer = container
        )

        val result = service.executeBuy(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Success, "Expected Success but got $result")
        verify { playerInv.removeItem(any()) }
        verify { containerInv.addItem(any()) }
        verify { economy.withdraw(ownerUuid, 50L) }
        verify { economy.deposit(playerUuid, 50L) }
    }

    // ===== BUY: PostShopTransactionEvent fired on success =====

    @Test
    fun `executeBuy fires PostShopTransactionEvent with correct fields`() {
        val server = MockBukkit.mock()
        try {
            val shop = testShop(costAmount = 50)

            val stallRepo = mockk<StallRepository>(relaxed = true)
            every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

            val economy = mockk<EconomyProvider>(relaxed = true)
            every { economy.balance(ownerUuid) } returns 100L
            every { economy.withdraw(ownerUuid, 50L) } returns true
            every { economy.deposit(playerUuid, 50L) } returns true

            val playerInv = mockk<PlayerInventory>(relaxed = true)
            every { playerInv.containsAtLeast(any<ItemStack>(), any()) } returns true

            val player = mockk<Player>(relaxed = true)
            every { player.inventory } returns playerInv

            mockkStatic(Bukkit::class)
            every { Bukkit.getPlayer(playerUuid) } returns player
            every { Bukkit.getPluginManager() } returns server.pluginManager

            val containerInv = mockk<Inventory>(relaxed = true)
            every { containerInv.addItem(any()) } returns hashMapOf()

            val container = mockk<Container>(relaxed = true)
            every { container.inventory } returns containerInv

            val mockItem = mockk<ItemStack>(relaxed = true)
            val service = buildService(
                stallRepo = stallRepo,
                economy = economy,
                mockItemStack = mockItem,
                mockContainer = container
            )

            val result = service.executeBuy(shop, playerUuid)
            assertTrue(result is ContainerTradeResult.Success, "Expected Success but got $result")

            // Verify PostShopTransactionEvent was fired with correct fields
            server.pluginManager.assertEventFired(PostShopTransactionEvent::class.java) { event ->
                event.buyer == player &&
                        event.landlordId == ownerUuid &&
                        event.item == mockItem &&
                        event.quantity == 1 &&
                        event.pricePaid == 50.0
            }
        } finally {
            MockBukkit.unmock()
        }
    }

    // ===== BUY: Insufficient player items =====

    @Test
    fun `executeBuy fails when player lacks items`() {
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val playerInv = mockk<PlayerInventory>(relaxed = true)
        every { playerInv.containsAtLeast(any<ItemStack>(), any()) } returns false

        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player

        val service = buildService(stallRepo = stallRepo)

        val result = service.executeBuy(testShop(), playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure for lacking items")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("don't have", ignoreCase = true))
    }

    // ===== BUY: Owner can't afford =====

    @Test
    fun `executeBuy fails when owner can't afford`() {
        val shop = testShop(costAmount = 500)

        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val economy = mockk<EconomyProvider>(relaxed = true)
        every { economy.balance(ownerUuid) } returns 50L // less than 500

        val playerInv = mockk<PlayerInventory>(relaxed = true)
        every { playerInv.containsAtLeast(any<ItemStack>(), any()) } returns true

        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player

        val service = buildService(stallRepo = stallRepo, economy = economy)

        val result = service.executeBuy(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure for owner can't afford")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("can't afford", ignoreCase = true))
    }

    // ===== BUY: Container full =====

    @Test
    fun `executeBuy rolls back when container is full`() {
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val economy = mockk<EconomyProvider>(relaxed = true)
        every { economy.balance(ownerUuid) } returns 100L

        val playerInv = mockk<PlayerInventory>(relaxed = true)
        every { playerInv.containsAtLeast(any<ItemStack>(), any()) } returns true

        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player

        val containerInv = mockk<Inventory>(relaxed = true)
        // Simulate container full — addItem returns a map with leftover items
        every { containerInv.addItem(any()) } returns hashMapOf(0 to mockk<ItemStack>(relaxed = true))

        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv

        val service = buildService(
            stallRepo = stallRepo,
            economy = economy,
            mockContainer = container
        )

        val result = service.executeBuy(testShop(), playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure for full container")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("Container is full", ignoreCase = true))

        // Verify rollback: item returned to player
        verify { playerInv.addItem(any()) }
    }

    // ===== BUY: Withdraw fails after item moved =====

    @Test
    fun `executeBuy compensation fails when owner withdraw fails after item moved`() {
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val economy = mockk<EconomyProvider>(relaxed = true)
        every { economy.balance(ownerUuid) } returns 100L
        every { economy.withdraw(ownerUuid, any()) } returns false

        val playerInv = mockk<PlayerInventory>(relaxed = true)
        every { playerInv.containsAtLeast(any<ItemStack>(), any()) } returns true

        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player

        val containerInv = mockk<Inventory>(relaxed = true)
        every { containerInv.addItem(any()) } returns hashMapOf()

        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv

        val service = buildService(
            stallRepo = stallRepo,
            economy = economy,
            mockContainer = container
        )

        val result = service.executeBuy(testShop(), playerUuid)
        assertTrue(result is ContainerTradeResult.CompensationFailed, "Expected CompensationFailed but got $result")
        // Verify rollback: remove item from container and return to player
        verify { containerInv.removeItem(any()) }
        verify { playerInv.addItem(any()) }
    }

    // ===== SELL: Success =====

    @Test
    fun `executeSell succeeds when container has stock and player has funds`() {
        val shop = testShop(costAmount = 75)

        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val economy = mockk<EconomyProvider>(relaxed = true)
        every { economy.balance(playerUuid) } returns 200L
        every { economy.withdraw(playerUuid, 75L) } returns true
        every { economy.deposit(ownerUuid, 75L) } returns true

        val playerInv = mockk<PlayerInventory>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player
        every { Bukkit.getPluginManager() } returns mockk(relaxed = true)

        val containerInv = mockk<Inventory>(relaxed = true)
        every { containerInv.containsAtLeast(any<ItemStack>(), any()) } returns true
        every { playerInv.addItem(any()) } returns hashMapOf()

        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv

        val service = buildService(
            stallRepo = stallRepo,
            economy = economy,
            mockContainer = container
        )

        val result = service.executeSell(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Success, "Expected Success but got $result")
        verify { economy.withdraw(playerUuid, 75L) }
        verify { economy.deposit(ownerUuid, 75L) }
        verify { containerInv.removeItem(any()) }
        verify { playerInv.addItem(any()) }
    }

    // ===== SELL: Out of stock =====

    @Test
    fun `executeSell fails when container out of stock`() {
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns mockk(relaxed = true)

        val containerInv = mockk<Inventory>(relaxed = true)
        every { containerInv.containsAtLeast(any<ItemStack>(), any()) } returns false

        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv

        val service = buildService(stallRepo = stallRepo, mockContainer = container)

        val result = service.executeSell(testShop(), playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure for out of stock")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("stock", ignoreCase = true))
    }

    // ===== SELL: Insufficient funds =====

    @Test
    fun `executeSell fails when player has insufficient funds`() {
        val shop = testShop(costAmount = 500)

        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val economy = mockk<EconomyProvider>(relaxed = true)
        every { economy.balance(playerUuid) } returns 50L

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns mockk(relaxed = true)

        val containerInv = mockk<Inventory>(relaxed = true)
        every { containerInv.containsAtLeast(any<ItemStack>(), any()) } returns true

        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv

        val service = buildService(
            stallRepo = stallRepo,
            economy = economy,
            mockContainer = container
        )

        val result = service.executeSell(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure for insufficient funds")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("Insufficient", ignoreCase = true))
    }

    // ===== SELL: Player inventory full after item removed from container =====

    @Test
    fun `executeSell compensation fails when player inventory full`() {
        val shop = testShop(costAmount = 50)

        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val economy = mockk<EconomyProvider>(relaxed = true)
        every { economy.balance(playerUuid) } returns 200L
        every { economy.withdraw(playerUuid, 50L) } returns true
        every { economy.deposit(ownerUuid, 50L) } returns true
        every { economy.withdraw(ownerUuid, 50L) } returns true
        every { economy.deposit(playerUuid, 50L) } returns true

        val playerInv = mockk<PlayerInventory>(relaxed = true)
        // Player inventory is full — addItem returns leftover
        every { playerInv.addItem(any()) } returns hashMapOf(0 to mockk<ItemStack>(relaxed = true))

        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player

        val containerInv = mockk<Inventory>(relaxed = true)
        every { containerInv.containsAtLeast(any<ItemStack>(), any()) } returns true

        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv

        val service = buildService(
            stallRepo = stallRepo,
            economy = economy,
            mockContainer = container
        )

        val result = service.executeSell(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.CompensationFailed, "Expected CompensationFailed but got $result")
        // Verify rollback: item returned to container, economy reversed
        verify { containerInv.addItem(any()) }
        verify { economy.withdraw(ownerUuid, 50L) }
        verify { economy.deposit(playerUuid, 50L) }
    }

    // ===== Invalid owner UUID =====

    @Test
    fun `executeBuy fails with invalid owner UUID`() {
        val ownerRef = OwnerRef(OwnerRef.solo(ownerUuid).type, "not-a-uuid")
        val badStall = sampleStall().copy(owner = ownerRef)

        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns badStall

        val service = buildService(stallRepo = stallRepo)

        val result = service.executeBuy(testShop(), playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure for invalid owner")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("Invalid owner", ignoreCase = true))
    }
}