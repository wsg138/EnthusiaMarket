package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.SignDirection
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.util.UUID

class ShopFactoryTest {

    @BeforeTest fun setup() { MockBukkit.mock() }
    @AfterTest fun teardown() { MockBukkit.unmock() }

    @Test fun `builds a SELL shop with base64 sell item and emerald cost hint`() {
        val sellStack = ItemStack(Material.DIAMOND, 5)
        val sellItemB64 = ItemStackSerializer.serialize(sellStack.clone().apply { amount = 1 })
        val owner = UUID.randomUUID()
        val shop = ShopFactory.build(
            stallId = "stall1", owner = owner, creator = owner,
            signWorld = "world", signX = 1, signY = 2, signZ = 3,
            containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
            sellItemBase64 = sellItemB64, sellAmount = 5, price = 100,
            direction = SignDirection.SELL,
        )
        assertEquals("stall1", shop.stallId)
        assertEquals(5, shop.sellAmount)
        assertEquals(100, shop.costAmount)
        assertEquals(SignDirection.SELL, shop.direction)
        // sellItem round-trips to a diamond.
        val decoded = ItemStackSerializer.deserialize(shop.sellItem)
        assertNotNull(decoded)
        assertEquals(Material.DIAMOND, decoded.type)
        // costItem is the emerald UI hint.
        val cost = ItemStackSerializer.deserialize(shop.costItem)
        assertNotNull(cost)
        assertEquals(Material.EMERALD, cost.type)
    }

    @Test fun `price above Int MAX is clamped`() {
        val owner = UUID.randomUUID()
        val sell = ItemStackSerializer.serialize(ItemStack(Material.DIRT, 1))
        val shop = ShopFactory.build(
            stallId = "s", owner = owner, creator = owner,
            signWorld = "world", signX = 0, signY = 0, signZ = 0,
            containerWorld = "world", containerX = 0, containerY = 0, containerZ = 0,
            sellItemBase64 = sell, sellAmount = 1, price = Long.MAX_VALUE,
            direction = SignDirection.SELL,
        )
        assertEquals(Int.MAX_VALUE, shop.costAmount)
    }
}