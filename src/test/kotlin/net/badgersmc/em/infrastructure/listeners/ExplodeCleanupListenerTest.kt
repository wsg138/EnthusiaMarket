package net.badgersmc.em.infrastructure.listeners

import io.mockk.*
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import org.bukkit.ExplosionResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.entity.Entity
import org.bukkit.event.entity.EntityExplodeEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import kotlin.test.Test

class ExplodeCleanupListenerTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `explosion destroys container with linked shops cleans them up`() {
        val repo = mockk<ShopRepository>(relaxed = true)

        val container = mockk<Container>(relaxed = true)
        val block = mockk<Block>(relaxed = true)
        every { block.state } returns container
        val loc = mockk<Location>(relaxed = true)
        every { loc.world?.name } returns "world"
        every { loc.blockX } returns 50
        every { loc.blockY } returns 64
        every { loc.blockZ } returns 60
        every { block.location } returns loc

        val shop1 = Shop(
            id = 1L, stallId = "s1", owner = UUID.randomUUID(),
            signWorld = "w", signX = 1, signY = 2, signZ = 3,
            containerWorld = "w", containerX = 50, containerY = 64, containerZ = 60,
            sellItem = "a", sellAmount = 1, costItem = "b", costAmount = 1
        )
        val shop2 = Shop(
            id = 2L, stallId = "s2", owner = UUID.randomUUID(),
            signWorld = "w", signX = 2, signY = 2, signZ = 3,
            containerWorld = "w", containerX = 50, containerY = 64, containerZ = 60,
            sellItem = "a", sellAmount = 1, costItem = "b", costAmount = 1
        )
        every { repo.findByContainer("world", 50, 64, 60) } returns listOf(shop1, shop2)

        val entity = mockk<Entity>(relaxed = true)
        val event = EntityExplodeEvent(entity, loc, listOf(block), 1.0f, ExplosionResult.DESTROY)
        val listener = ExplodeCleanupListener(repo)

        listener.onExplode(event)

        verify { repo.delete(1L) }
        verify { repo.delete(2L) }
    }

    @Test
    fun `explosion without containers does nothing`() {
        val repo = mockk<ShopRepository>(relaxed = true)

        val block = mockk<Block>(relaxed = true)
        val state = mockk<org.bukkit.block.BlockState>(relaxed = true)
        every { block.state } returns state

        val entity = mockk<Entity>(relaxed = true)
        val loc = mockk<Location>(relaxed = true)
        val event = EntityExplodeEvent(entity, loc, listOf(block), 1.0f, ExplosionResult.DESTROY)

        val listener = ExplodeCleanupListener(repo)
        listener.onExplode(event)

        verify(exactly = 0) { repo.delete(any()) }
    }

    @Test
    fun `explosion destroys container without linked shops does nothing`() {
        val repo = mockk<ShopRepository>(relaxed = true)

        val container = mockk<Container>(relaxed = true)
        val block = mockk<Block>(relaxed = true)
        every { block.state } returns container
        val loc = mockk<Location>(relaxed = true)
        every { loc.world?.name } returns "world"
        every { loc.blockX } returns 50
        every { loc.blockY } returns 64
        every { loc.blockZ } returns 60
        every { block.location } returns loc

        every { repo.findByContainer("world", 50, 64, 60) } returns emptyList()

        val entity = mockk<Entity>(relaxed = true)
        val event = EntityExplodeEvent(entity, loc, listOf(block), 1.0f, ExplosionResult.DESTROY)

        val listener = ExplodeCleanupListener(repo)
        listener.onExplode(event)

        verify(exactly = 0) { repo.delete(any()) }
    }
}
