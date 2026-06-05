package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
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
        vaultService: ShopVaultService = mockk(relaxed = true),
        mockCostStack: ItemStack = mockk(relaxed = true),
        mockContainer: Container = mockk(relaxed = true)
    ): ContainerTradeService {
        return object : ContainerTradeService(stallRepo, economy, guildProvider, vaultService) {
            override fun deserializeStack(base64: String): ItemStack? = mockCostStack
            override fun getContainer(shop: Shop): Container? = mockContainer
        }
    }

    @Test
    fun `executeTrade succeeds deposits vault and moves stock`() {
        val shop = testShop(sellAmount = 2, costAmount = 3)
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val vaultService = mockk<ShopVaultService>(relaxed = true)

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

        val service = object : ContainerTradeService(stallRepo, mockk(relaxed = true), null, vaultService) {
            override fun deserializeStack(base64: String): ItemStack? = costStack
            override fun getContainer(shop: Shop): Container? = container
        }

        val result = service.executeTrade(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Success, "Expected Success but got $result")
        verify { playerInv.removeItem(any()) }
        verify { vaultService.deposit(ownerUuid, any(), 3) }
        verify { containerInv.removeItem(any()) }
        verify { playerInv.addItem(any()) }
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

        val service = buildService(stallRepo = stallRepo, mockContainer = mockk(relaxed = true))
        val result = service.executeTrade(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure)
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("don't have", ignoreCase = true))
    }

    @Test
    fun `executeTrade fails when chest out of stock`() {
        val shop = testShop(sellAmount = 2)
        val stallRepo = mockk<StallRepository>(relaxed = true)
        every { stallRepo.findById(StallId("stall_01")) } returns sampleStall()

        val playerInv = mockk<PlayerInventory>(relaxed = true)
        every { playerInv.containsAtLeast(any<ItemStack>(), any()) } returns true

        val player = mockk<Player>(relaxed = true)
        every { player.inventory } returns playerInv

        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(playerUuid) } returns player

        val containerInv = mockk<Inventory>(relaxed = true)
        every { containerInv.containsAtLeast(any<ItemStack>(), any()) } returns false

        val container = mockk<Container>(relaxed = true)
        every { container.inventory } returns containerInv

        val service = buildService(stallRepo = stallRepo, mockContainer = container)
        val result = service.executeTrade(shop, playerUuid)
        assertTrue(result is ContainerTradeResult.Failure)
        assertTrue((result as ContainerTradeResult.Failure).reason.contains("Out of stock", ignoreCase = true))
    }
}
