package net.badgersmc.em.infrastructure.listeners

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ShopCreateListenerRoutingTest {

    @BeforeTest fun setup() { MockBukkit.mock() }
    @AfterTest fun teardown() { MockBukkit.unmock() }

    @Test fun `empty hand yields null sell item`() {
        val air = ItemStack(Material.AIR)
        assertNull(ShopCreateListener.captureSellItem(air))
    }

    @Test fun `held item yields base64 with amount normalised to one`() {
        val held = ItemStack(Material.DIAMOND, 16)
        val b64 = ShopCreateListener.captureSellItem(held)
        assertNotNull(b64)
        val decoded = net.badgersmc.em.application.ItemStackSerializer.deserialize(b64)
        assertNotNull(decoded)
        kotlin.test.assertEquals(Material.DIAMOND, decoded.type)
        kotlin.test.assertEquals(1, decoded.amount)
    }
}