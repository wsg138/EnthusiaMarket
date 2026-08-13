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

    @Test fun `builds a SELL shop with base64 sell item and raw_gold cost hint`() {
        val sellStack = ItemStack(Material.DIAMOND, 5)
        val sellItemB64 = ItemStackSerializer.serialize(sellStack.clone().apply { amount = 1 })
        val owner = UUID.randomUUID()
        val shop = ShopFactory.build(ShopFactory.Input(
            stallId = "stall1",
            owner = owner,
            sign = ShopFactory.Position("world", 1, 2, 3),
            container = ShopFactory.Position("world", 1, 1, 1),
            sell = ShopFactory.ItemAmount(sellItemB64, 5),
            cost = ShopFactory.Cost(100),
            direction = SignDirection.SELL,
        ))
        assertEquals("stall1", shop.stallId)
        assertEquals(5, shop.sellAmount)
        assertEquals(100, shop.costAmount)
        assertEquals(SignDirection.SELL, shop.direction)
        // sellItem round-trips to a diamond.
        val decoded = ItemStackSerializer.deserialize(shop.sellItem)
        assertNotNull(decoded)
        assertEquals(Material.DIAMOND, decoded.type)
        // costItem is the RAW_GOLD UI hint.
        val cost = ItemStackSerializer.deserialize(shop.costItem)
        assertNotNull(cost)
        assertEquals(Material.RAW_GOLD, cost.type)
    }

    @Test fun `price above Int MAX is clamped`() {
        val owner = UUID.randomUUID()
        val sell = ItemStackSerializer.serialize(ItemStack(Material.DIRT, 1))
        val shop = ShopFactory.build(ShopFactory.Input(
            stallId = "s",
            owner = owner,
            sign = ShopFactory.Position("world", 0, 0, 0),
            container = ShopFactory.Position("world", 0, 0, 0),
            sell = ShopFactory.ItemAmount(sell, 1),
            cost = ShopFactory.Cost(Long.MAX_VALUE),
            direction = SignDirection.SELL,
        ))
        assertEquals(Int.MAX_VALUE, shop.costAmount)
    }
}
