package net.badgersmc.em.interaction.gui

import net.badgersmc.em.application.ShopFactory
import net.badgersmc.em.domain.shop.SignDirection
import org.mockbukkit.mockbukkit.MockBukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * TDD-289 RED — ShopFactory doesn't accept costItem params for TRADE shops.
 * CreateShopMenu hardcodes direction=SELL. Both paths need fixing.
 */
class CreateShopMenuDirectionTest {

    @AfterEach
    fun unmock() {
        if (MockBukkit.isMocked()) MockBukkit.unmock()
    }

    @Test
    fun `shop factory supports all directions and custom cost items via costItemBase64`() {
        MockBukkit.mock()
        try {
            val shop = ShopFactory.build(
                stallId = "stall1",
                owner = UUID.randomUUID(),
                creator = UUID.randomUUID(),
                signWorld = "world", signX = 100, signY = 64, signZ = 200,
                containerWorld = "world", containerX = 50, containerY = 64, containerZ = 60,
                sellItemBase64 = "base64item",
                sellAmount = 64,
                price = 100,
                direction = SignDirection.TRADE,
                searchEnabled = true,
                costItemBase64 = "base64cost",
                costAmountOverride = 32,
            )
            assertEquals(SignDirection.TRADE, shop.direction)
            assertEquals("base64cost", shop.costItem)
            assertEquals(32, shop.costAmount)
        } finally {
            MockBukkit.unmock()
        }
    }
}
