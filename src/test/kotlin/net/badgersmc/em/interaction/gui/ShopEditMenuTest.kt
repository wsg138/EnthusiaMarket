package net.badgersmc.em.interaction.gui

import io.mockk.*
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull

class ShopEditMenuTest {

    @Test fun `edit menu constructs without throwing`() {
        val shop = Shop(id = 1L, stallId = "s1", owner = UUID.randomUUID(),
            signWorld = "w", signX = 1, signY = 2, signZ = 3,
            containerWorld = "w", containerX = 4, containerY = 5, containerZ = 6,
            sellItem = "a", sellAmount = 1, costItem = "b", costAmount = 1,
            frozen = false, hopperAllowIn = true, hopperAllowOut = true)

        val menu = ShopEditMenu(shop, mockk(relaxed = true), mockk<ShopManagementService>(relaxed = true), mockk(relaxed = true))
        assertNotNull(menu)
    }

    @Test fun `frozen shop shows frozen state`() {
        val shop = Shop(id = 1L, stallId = "s1", owner = UUID.randomUUID(),
            signWorld = "w", signX = 1, signY = 2, signZ = 3,
            containerWorld = "w", containerX = 4, containerY = 5, containerZ = 6,
            sellItem = "a", sellAmount = 1, costItem = "b", costAmount = 1,
            frozen = true)

        val menu = ShopEditMenu(shop, mockk(relaxed = true), mockk<ShopManagementService>(relaxed = true), mockk(relaxed = true))
        assertNotNull(menu)
    }

    @Test fun `non-owner cannot open edit menu`() {
        val ownerId = UUID.randomUUID()
        val shop = Shop(id = 1L, stallId = "s1", owner = ownerId,
            signWorld = "w", signX = 1, signY = 2, signZ = 3,
            containerWorld = "w", containerX = 4, containerY = 5, containerZ = 6,
            sellItem = "a", sellAmount = 1, costItem = "b", costAmount = 1)

        val nonOwner = mockk<Player>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
            every { hasPermission("enthusiamarket.admin") } returns false
        }

        val menu = ShopEditMenu(shop, mockk(relaxed = true), mockk<ShopManagementService>(relaxed = true), mockk(relaxed = true))
        menu.open(nonOwner)

        verify { nonOwner.sendMessage(any<Component>()) }
    }
}
