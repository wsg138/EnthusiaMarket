package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LookAtShopResolverTest {

    private fun shop(id: Long) = Shop(
        id = id, stallId = "s", owner = UUID.randomUUID(),
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "s", sellAmount = 1, costItem = "c", costAmount = 10,
        direction = SignDirection.SELL,
    )

    @Test fun `resolves a sign block to its shop`() {
        val repo = mockk<ShopRepository>()
        every { repo.findBySign("world", 1, 2, 3) } returns shop(7)
        assertEquals(7L, LookAtShopResolver(repo).resolve("world", 1, 2, 3)?.id)
    }

    @Test fun `falls back to the first container shop`() {
        val repo = mockk<ShopRepository>()
        every { repo.findBySign("world", 5, 6, 7) } returns null
        every { repo.findByContainer("world", 5, 6, 7) } returns listOf(shop(9), shop(10))
        assertEquals(9L, LookAtShopResolver(repo).resolve("world", 5, 6, 7)?.id)
    }

    @Test fun `null world resolves to null`() {
        assertNull(LookAtShopResolver(mockk()).resolve(null, 0, 0, 0))
    }

    @Test fun `no shop at the coords resolves to null`() {
        val repo = mockk<ShopRepository>()
        every { repo.findBySign("world", 1, 1, 1) } returns null
        every { repo.findByContainer("world", 1, 1, 1) } returns emptyList()
        assertNull(LookAtShopResolver(repo).resolve("world", 1, 1, 1))
    }
}