package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.application.AdminBreakMode
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.events.ShopDeletedEvent
import org.bukkit.ExplosionResult
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID

class ShopDeletedEventTest {

    private lateinit var server: ServerMock
    private val ownerId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun makeShop(id: Long = 42L): Shop = Shop(
        id = id,
        stallId = "stall_01",
        owner = ownerId,
        signWorld = "world", signX = 100, signY = 64, signZ = 200,
        containerWorld = "world", containerX = 50, containerY = 64, containerZ = 60,
        sellItem = "item", sellAmount = 1, costItem = "cost", costAmount = 5
    )

    private fun mockContainerBlock(x: Int = 50, y: Int = 64, z: Int = 60): Block {
        val loc = mockk<Location>(relaxed = true)
        every { loc.world?.name } returns "world"
        every { loc.blockX } returns x
        every { loc.blockY } returns y
        every { loc.blockZ } returns z
        val block = mockk<Block>(relaxed = true)
        every { block.state } returns mockk<Container>(relaxed = true)
        every { block.location } returns loc
        return block
    }

    @Test
    fun `container break by owner fires ShopDeletedEvent with correct owner UUID`() {
        // ── Given: an owner breaking a container with a linked shop ──
        val repo = mockk<ShopRepository>(relaxed = true)
        val shop = makeShop()
        every { repo.findByContainer("world", 50, 64, 60) } returns listOf(shop)

        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns ownerId
        every { player.name } returns "TestPlayer"
        every { player.hasPermission("enthusiamarket.admin") } returns false

        val block = mockContainerBlock()
        val breakEvent = BlockBreakEvent(block, player)
        val listener = BlockProtectionListener(repo, mockk<AdminBreakMode>(relaxed = true), mockk<ShopManagementService>(relaxed = true), mockk(relaxed = true))

        // ── When: the container is broken ──
        listener.onBlockBreak(breakEvent)

        // ── Then: ShopDeletedEvent should have been fired ──
        server.pluginManager.assertEventFired(ShopDeletedEvent::class.java) { e ->
            e.ownerId == ownerId
        }
    }

    @Test
    fun `explosion destroying container fires ShopDeletedEvent with correct owner UUID`() {
        // ── Given: an explosion that destroys a container with linked shops ──
        val repo = mockk<ShopRepository>(relaxed = true)
        val shop = makeShop()
        every { repo.findByContainer("world", 50, 64, 60) } returns listOf(shop)

        val block = mockContainerBlock()
        val entity = mockk<Entity>(relaxed = true)
        val loc = mockk<Location>(relaxed = true)
        val explodeEvent = EntityExplodeEvent(entity, loc, listOf(block), 1.0f, ExplosionResult.DESTROY)
        val listener = ExplodeCleanupListener(repo)

        // ── When: the explosion happens ──
        listener.onExplode(explodeEvent)

        // ── Then: ShopDeletedEvent should have been fired ──
        server.pluginManager.assertEventFired(ShopDeletedEvent::class.java) { e ->
            e.ownerId == ownerId
        }
    }
}