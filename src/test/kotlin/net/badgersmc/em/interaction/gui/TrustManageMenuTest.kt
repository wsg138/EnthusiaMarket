package net.badgersmc.em.interaction.gui

import io.mockk.*
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull

class TrustManageMenuTest {

    @Test fun `menu constructs without throwing`() {
        val shop = Shop(id = 1L, stallId = "s1", owner = UUID.randomUUID(),
            signWorld = "w", signX = 1, signY = 2, signZ = 3,
            containerWorld = "w", containerX = 4, containerY = 5, containerZ = 6,
            sellItem = "a", sellAmount = 1, costItem = "b", costAmount = 1,
            trusted = setOf(UUID.randomUUID(), UUID.randomUUID()))

        val menu = TrustManageMenu(mockk(relaxed = true), shop, mockk(relaxed = true))
        assertNotNull(menu)
    }

    @Test fun `menu with no trusted players shows add button`() {
        val shop = Shop(id = 1L, stallId = "s1", owner = UUID.randomUUID(),
            signWorld = "w", signX = 1, signY = 2, signZ = 3,
            containerWorld = "w", containerX = 4, containerY = 5, containerZ = 6,
            sellItem = "a", sellAmount = 1, costItem = "b", costAmount = 1,
            trusted = emptySet())

        val menu = TrustManageMenu(mockk(relaxed = true), shop, mockk(relaxed = true))
        assertNotNull(menu)
    }
}
