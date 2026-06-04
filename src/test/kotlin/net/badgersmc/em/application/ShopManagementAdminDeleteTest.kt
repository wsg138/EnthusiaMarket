package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopManagementAdminDeleteTest {

    private fun shop(id: Long, owner: UUID) = Shop(
        id = id, stallId = "s", owner = owner,
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "s", sellAmount = 1, costItem = "c", costAmount = 10,
        direction = SignDirection.SELL,
    )

    @Test fun `deletes a shop owned by someone else`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val someoneElse = UUID.randomUUID()
        every { repo.findById(42) } returns shop(42, someoneElse)
        val svc = ShopManagementService(repo)

        assertTrue(svc.adminDelete(42))
        verify { repo.delete(42) }
    }

    @Test fun `returns false for a missing shop and never deletes`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(99) } returns null
        assertFalse(ShopManagementService(repo).adminDelete(99))
        verify(exactly = 0) { repo.delete(99) }
    }
}
