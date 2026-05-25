package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test

class BlockProtectionListenerTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun mockBlock(
        state: org.bukkit.block.BlockState,
        worldName: String = "world",
        x: Int = 0,
        y: Int = 0,
        z: Int = 0
    ): Block {
        val loc = mockk<Location>(relaxed = true)
        every { loc.world?.name } returns worldName
        every { loc.blockX } returns x
        every { loc.blockY } returns y
        every { loc.blockZ } returns z
        val block = mockk<Block>(relaxed = true)
        every { block.state } returns state
        every { block.location } returns loc
        return block
    }

    private fun mockSign(state: Sign = mockk(relaxed = true), x: Int = 0, y: Int = 0, z: Int = 0) =
        mockBlock(state, x = x, y = y, z = z)

    private fun mockContainer(state: Container = mockk(relaxed = true), x: Int = 0, y: Int = 0, z: Int = 0) =
        mockBlock(state, x = x, y = y, z = z)

    private fun mockPlainBlock() = mockk<Block>(relaxed = true)

    private fun makeShop(
        id: Long = 1,
        owner: UUID = UUID.randomUUID(),
        trusted: Set<UUID> = emptySet(),
        signX: Int = 100,
        signY: Int = 64,
        signZ: Int = 200,
        containerX: Int = 50,
        containerY: Int = 64,
        containerZ: Int = 60
    ) = Shop(
        id = id,
        stallId = "stall_01",
        owner = owner,
        signWorld = "world", signX = signX, signY = signY, signZ = signZ,
        containerWorld = "world", containerX = containerX, containerY = containerY, containerZ = containerZ,
        sellItem = "item", sellAmount = 1, costItem = "cost", costAmount = 5,
        trusted = trusted
    )

    // ── Sign break scenarios ──────────────────────────────────────────────

    @Test
    fun `breaking a shop sign as non-owner is cancelled with cannot-break message`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val ownerId = UUID.randomUUID()
        val shop = makeShop(owner = ownerId, signX = 100, signY = 64, signZ = 200)
        every { repo.findBySign("world", 100, 64, 200) } returns shop

        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID() // different from owner
        every { player.hasPermission("enthusiamarket.admin") } returns false

        val block = mockSign(x = 100, y = 64, z = 200)
        val event = BlockBreakEvent(block, player)
        val listener = BlockProtectionListener(repo, mockk<Logger>(relaxed = true))

        listener.onBlockBreak(event)

        assert(event.isCancelled) { "Non-owner sign break should be cancelled" }
        verify { player.sendMessage("§cYou cannot break this shop sign") }
    }

    @Test
    fun `breaking a shop sign as owner is cancelled with edit-menu message`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val ownerId = UUID.randomUUID()
        val shop = makeShop(owner = ownerId, signX = 100, signY = 64, signZ = 200)
        every { repo.findBySign("world", 100, 64, 200) } returns shop

        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns ownerId
        every { player.hasPermission("enthusiamarket.admin") } returns false

        val block = mockSign(x = 100, y = 64, z = 200)
        val event = BlockBreakEvent(block, player)
        val listener = BlockProtectionListener(repo, mockk<Logger>(relaxed = true))

        listener.onBlockBreak(event)

        assert(event.isCancelled) { "Owner sign break should be cancelled" }
        verify { player.sendMessage("§e[Shop] Use the edit menu to delete this shop (coming in TDD-57)") }
    }

    @Test
    fun `breaking a shop sign as trusted player is cancelled with edit-menu message`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val ownerId = UUID.randomUUID()
        val trustedId = UUID.randomUUID()
        val shop = makeShop(owner = ownerId, trusted = setOf(trustedId), signX = 100, signY = 64, signZ = 200)
        every { repo.findBySign("world", 100, 64, 200) } returns shop

        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns trustedId
        every { player.hasPermission("enthusiamarket.admin") } returns false

        val block = mockSign(x = 100, y = 64, z = 200)
        val event = BlockBreakEvent(block, player)
        val listener = BlockProtectionListener(repo, mockk<Logger>(relaxed = true))

        listener.onBlockBreak(event)

        assert(event.isCancelled) { "Trusted sign break should be cancelled" }
        verify { player.sendMessage("§e[Shop] Use the edit menu to delete this shop (coming in TDD-57)") }
    }

    // ── Container break scenarios ─────────────────────────────────────────

    @Test
    fun `breaking a container with linked shops as owner deletes shops`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val ownerId = UUID.randomUUID()
        val shop = makeShop(id = 42L, owner = ownerId, containerX = 50, containerY = 64, containerZ = 60)
        every { repo.findByContainer("world", 50, 64, 60) } returns listOf(shop)

        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns ownerId
        every { player.name } returns "TestPlayer"
        every { player.hasPermission("enthusiamarket.admin") } returns false

        val block = mockContainer(x = 50, y = 64, z = 60)
        val event = BlockBreakEvent(block, player)
        val listener = BlockProtectionListener(repo, mockk<Logger>(relaxed = true))

        listener.onBlockBreak(event)

        // Event should NOT be cancelled for owner
        assert(!event.isCancelled) { "Owner container break should NOT be cancelled" }
        verify { repo.deleteByContainer("world", 50, 64, 60) }
        verify { player.sendMessage("§aDeleted 1 shop(s) linked to this container") }
    }

    @Test
    fun `breaking a container with linked shops as non-owner is cancelled`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val ownerId = UUID.randomUUID()
        val shop = makeShop(id = 42L, owner = ownerId, containerX = 50, containerY = 64, containerZ = 60)
        every { repo.findByContainer("world", 50, 64, 60) } returns listOf(shop)

        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID() // different from owner
        every { player.hasPermission("enthusiamarket.admin") } returns false

        val block = mockContainer(x = 50, y = 64, z = 60)
        val event = BlockBreakEvent(block, player)
        val listener = BlockProtectionListener(repo, mockk<Logger>(relaxed = true))

        listener.onBlockBreak(event)

        assert(event.isCancelled) { "Non-owner container break should be cancelled" }
        verify(exactly = 0) { repo.delete(any<Long>()) }
        verify { player.sendMessage("§cThis container has active shops. Only the owner can break it.") }
    }

    // ── Pass-through scenarios ────────────────────────────────────────────

    @Test
    fun `breaking a container without linked shops is not cancelled`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findByContainer("world", 0, 0, 0) } returns emptyList()

        val player: Player = mockk(relaxed = true)

        val block = mockContainer()
        val event = BlockBreakEvent(block, player)
        val listener = BlockProtectionListener(repo, mockk<Logger>(relaxed = true))

        listener.onBlockBreak(event)

        assert(!event.isCancelled) { "Container without shops should pass through" }
    }

    @Test
    fun `breaking a non-sign non-container block is not cancelled`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val player: Player = mockk(relaxed = true)
        val block = mockPlainBlock()
        // block.state returns null by default from relaxed mockk

        val event = BlockBreakEvent(block, player)
        val listener = BlockProtectionListener(repo, mockk<Logger>(relaxed = true))

        listener.onBlockBreak(event)

        assert(!event.isCancelled) { "Non-sign, non-container block should pass through" }
    }

    @Test
    fun `breaking a non-shop sign is not cancelled`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findBySign("world", 100, 64, 200) } returns null

        val player: Player = mockk(relaxed = true)

        val sign = mockk<Sign>(relaxed = true)
        val block = mockBlock(sign, x = 100, y = 64, z = 200)
        val event = BlockBreakEvent(block, player)
        val listener = BlockProtectionListener(repo, mockk<Logger>(relaxed = true))

        listener.onBlockBreak(event)

        assert(!event.isCancelled) { "Non-shop sign should pass through" }
    }
}