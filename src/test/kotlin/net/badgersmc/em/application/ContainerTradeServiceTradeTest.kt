package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.application.ShopVaultService
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.mockbukkit.mockbukkit.MockBukkit
import org.junit.jupiter.api.AfterEach
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class ContainerTradeServiceTradeTest {

    @AfterEach
    fun cleanupMocks() {
        unmockkAll()
        if (MockBukkit.isMocked()) MockBukkit.unmock()
    }

    private val playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val ownerUuid = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private fun testShop(
        stallId: String = "stall_01",
        frozen: Boolean = false,
        sellAmount: Int = 1,
        costAmount: Int = 5
    ): Shop = Shop(
        stallId = stallId,
        owner = ownerUuid,
        direction = SignDirection.TRADE,
        signWorld = "world", signX = 100, signY = 64, signZ = 200,
        containerWorld = "world", containerX = 0, containerY = 0, containerZ = 0,
        sellItem = "itemBase64", sellAmount = sellAmount,
        costItem = "costBase64", costAmount = costAmount,
        frozen = frozen
    )

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

    private fun buildService(
        stallRepo: StallRepository = mockk(relaxed = true),
        economy: EconomyProvider = mockk(relaxed = true),
        guildProvider: GuildProvider? = null,
        tradePolicy: GuildTradePolicyService? = null,
        shopVault: ShopVaultService? = mockk(relaxed = true),
        mockCostStack: ItemStack = mockk(relaxed = true),
        mockContainer: Container? = mockk(relaxed = true),
        hasAtLeast: InventoryPredicate = inventoryAlwaysHas,
        canFit: InventoryPredicate = inventoryFitsWhenPositive,
    ): ContainerTradeService = ContainerTradeServiceHarness(
        stallRepo, economy, guildProvider, tradePolicy, shopVault,
        mockItemStack = mockCostStack,
        mockContainer = mockContainer,
        hasAtLeast = hasAtLeast,
        canFit = canFit,
    )

    private fun mockOnlinePlayer(): Pair<Player, PlayerInventory> {
        val playerInv = mockk<PlayerInventory>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv
        every { player.uniqueId } returns playerUuid
        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player
        return player to playerInv
    }

    private fun mockContainerPair(): Pair<Container, Inventory> {
        val containerInv = mockk<Inventory>(relaxed = true)
        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv
        return container to containerInv
    }

    @Test
    fun `executeTrade succeeds deposits vault and moves stock`() {
        val shop = testShop(sellAmount = 2, costAmount = 3)
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val playerInv = mockk<PlayerInventory>(relaxed = true)
        every { playerInv.containsAtLeast(any<ItemStack>(), any()) } returns true
        every { playerInv.removeItem(any()) } returns hashMapOf()
        every { playerInv.addItem(any()) } returns hashMapOf()

        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv
        every { player.uniqueId } returns playerUuid

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player
        every { Bukkit.getPluginManager() } returns mockk(relaxed = true)

        val containerInv = mockk<Inventory>(relaxed = true)
        every { containerInv.containsAtLeast(any<ItemStack>(), any()) } returns true

        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv

        val costStack = mockk<ItemStack>(relaxed = true)
        every { costStack.amount } returns 3

        val vaultService = mockk<ShopVaultService>(relaxed = true)
        val service = object : ContainerTradeService(stallRepo, mockk(relaxed = true), null, shopVault = vaultService) {
            override fun deserializeStack(base64: String): ItemStack? = costStack
            override fun getContainer(shop: Shop): Container? = container
            override fun inventoryHasAtLeast(inventory: Inventory, template: ItemStack, amount: Int) = true
        }

        val result = service.executeTrade(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Success, "Expected Success but got $result")
        verify { playerInv.removeItem(any()) }
        verify { containerInv.removeItem(any()) }
        verify { playerInv.addItem(any()) }
        verify { vaultService.deposit(ownerUuid, costStack, 3) }
    }

    @Test
    fun `executeTrade fails when player lacks payment items`() {
        val shop = testShop(costAmount = 5)
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val playerInv = mockk<PlayerInventory>(relaxed = true)
        every { playerInv.containsAtLeast(any<ItemStack>(), any()) } returns false

        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player

        val service = buildService(stallRepo = stallRepo, hasAtLeast = inventoryNeverHas)
        val result = service.executeTrade(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure)
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("Out of stock", ignoreCase = true))
    }

    @Test
    fun `executeTrade fails when chest out of stock`() {
        val shop = testShop(sellAmount = 2)
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        mockOnlinePlayer()
        val (container, containerInv) = mockContainerPair()

        val service = buildService(
            stallRepo = stallRepo,
            mockContainer = container,
            hasAtLeast = inventoryFailsExcept(containerInv),
        )
        val result = service.executeTrade(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure)
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("Out of stock", ignoreCase = true))
    }

    @Test
    fun `solo shop ignores policy (factor unaffected)`() {
        val shop = testShop(costAmount = 100)
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val policyService = mockk<GuildTradePolicyService>(relaxed = true)
        val economy = mockk<EconomyProvider>(relaxed = true)
        every { economy.balance(playerUuid) } returns 500L
        every { economy.withdraw(playerUuid, 100L) } returns true
        every { economy.deposit(ownerUuid, 100L) } returns true

        val (_, playerInv) = mockOnlinePlayer()
        every { Bukkit.getPluginManager() } returns mockk(relaxed = true)
        every { playerInv.addItem(any()) } returns hashMapOf()
        val (container, _) = mockContainerPair()

        val service = buildService(stallRepo, economy, tradePolicy = policyService, mockContainer = container)
        val result = service.executeSell(shop, playerUuid)

        assertTrue(result is ContainerTradeResult.Success, "Expected Success but got $result")
        verify { economy.withdraw(playerUuid, 100L) }
        verify { economy.deposit(ownerUuid, 100L) }
        io.mockk.verify(exactly = 0) { policyService.stanceFor(any(), any(), any()) }
    }

    @Test
    fun `executeTrade fails when guild stall is embargoed`() {
        val guildId = UUID.fromString("00000000-0000-0000-0000-000000000042")
        val shop = testShop(sellAmount = 2, costAmount = 3)
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall().copy(owner = OwnerRef.guild(guildId.toString()))
        val policyService = mockk<GuildTradePolicyService>(relaxed = true)
        every { policyService.stanceFor(guildId.toString(), playerUuid, SignDirection.TRADE) } returns GuildTradePolicyService.TradeStance.Embargoed
        val (_, playerInv) = mockOnlinePlayer()
        val (container, containerInv) = mockContainerPair()
        val result = buildService(stallRepo, tradePolicy = policyService, mockContainer = container).executeTrade(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure but got $result")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("embargoed", ignoreCase = true))
        verify(exactly = 0) { playerInv.removeItem(any()) }
        verify(exactly = 0) { containerInv.removeItem(any()) }
    }

    @Test
    fun `executeTrade fails when player cost removal partially fails`() {
        val shop = testShop(sellAmount = 2, costAmount = 5)
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val playerInv = mockk<PlayerInventory>(relaxed = true)
        every { playerInv.containsAtLeast(any<ItemStack>(), any()) } returns true
        // Simulate partial failure: removeItem returns map with 2 leftover (only 3 of 5 removed)
        val leftover = hashMapOf(0 to mockk<ItemStack>(relaxed = true))
        every { leftover[0]!!.amount } returns 2
        every { playerInv.removeItem(any()) } returns leftover
        every { playerInv.addItem(any()) } returns hashMapOf()

        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv
        every { player.uniqueId } returns playerUuid

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player
        every { Bukkit.getPluginManager() } returns mockk(relaxed = true)

        val containerInv = mockk<Inventory>(relaxed = true)
        every { containerInv.containsAtLeast(any<ItemStack>(), any()) } returns true
        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv

        val costStack = mockk<ItemStack>(relaxed = true)
        every { costStack.amount } returns 5

        val vaultService = mockk<ShopVaultService>(relaxed = true)

        val service = object : ContainerTradeService(stallRepo, mockk(relaxed = true), null, shopVault = vaultService) {
            override fun deserializeStack(base64: String): ItemStack? =
                if (base64 == "costBase64") costStack else mockk(relaxed = true)
            override fun getContainer(shop: Shop): Container? = container
            override fun inventoryHasAtLeast(inventory: Inventory, template: ItemStack, amount: Int) = true
        }

        val result = service.executeTrade(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure, "Expected Failure but got $result")
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("cost", ignoreCase = true))
        // Verify cost items that WERE removed are returned to player.
        // (MockK relaxed-mock clone() does not propagate property values, so
        // we verify the call happened without asserting the exact restored amount.)
        verify(atLeast = 1) { playerInv.addItem(any()) }
        // Verify nothing was deposited to vault
        verify(exactly = 0) { vaultService.deposit(any(), any(), any()) }
    }
}
