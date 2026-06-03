package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopManagementServiceTest {

    private val owner = UUID.randomUUID()
    private val target = UUID.randomUUID()

    private fun shop(id: Long, owner: UUID = this.owner, trusted: Set<UUID> = emptySet()) = Shop(
        id = id, stallId = "stall1", owner = owner,
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "s", sellAmount = 1, costItem = "c", costAmount = 10,
        trusted = trusted, direction = SignDirection.SELL,
    )

    @Test fun `shopsOwnedBy delegates to repository`() {
        val repo = mockk<ShopRepository>()
        every { repo.findByOwner(owner) } returns listOf(shop(1), shop(2))
        val svc = ShopManagementService(repo)
        assertEquals(2, svc.shopsOwnedBy(owner).size)
    }

    @Test fun `trust adds target to listed shops owned by owner and persists`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(1) } returns shop(1)
        every { repo.findById(2) } returns shop(2)
        val svc = ShopManagementService(repo)
        val saved = mutableListOf<Shop>()
        every { repo.upsert(capture(saved)) } answers { firstArg() }

        val changed = svc.trust(owner, target, listOf(1L, 2L))
        assertEquals(2, changed)
        assertTrue(saved.all { target in it.trusted })
    }

    @Test fun `trust skips shops not owned by the actor`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(9) } returns shop(9, owner = UUID.randomUUID())
        val svc = ShopManagementService(repo)
        assertEquals(0, svc.trust(owner, target, listOf(9L)))
        verify(exactly = 0) { repo.upsert(any()) }
    }

    @Test fun `untrust removes target and persists`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(1) } returns shop(1, trusted = setOf(target))
        val svc = ShopManagementService(repo)
        val saved = slot<Shop>()
        every { repo.upsert(capture(saved)) } answers { firstArg() }
        assertEquals(1, svc.untrust(owner, target, listOf(1L)))
        assertFalse(target in saved.captured.trusted)
    }

    @Test fun `delete enforces ownership`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(1) } returns shop(1)
        every { repo.findById(2) } returns shop(2, owner = UUID.randomUUID())
        val svc = ShopManagementService(repo)
        assertTrue(svc.delete(owner, 1))
        assertFalse(svc.delete(owner, 2))
        verify(exactly = 1) { repo.delete(1) }
        verify(exactly = 0) { repo.delete(2) }
    }

    @Test fun `deleteAll removes every owned shop and counts`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findByOwner(owner) } returns listOf(shop(1), shop(2), shop(3))
        val svc = ShopManagementService(repo)
        assertEquals(3, svc.deleteAll(owner))
        verify { repo.delete(1) }
        verify { repo.delete(2) }
        verify { repo.delete(3) }
    }

    @Test fun `trustAll trusts target on every owned shop`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findByOwner(owner) } returns listOf(shop(1), shop(2))
        every { repo.findById(1) } returns shop(1)
        every { repo.findById(2) } returns shop(2)
        every { repo.upsert(any()) } answers { firstArg() }
        val svc = ShopManagementService(repo)
        assertEquals(2, svc.trustAll(owner, target))
    }
}
