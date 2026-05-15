package net.badgersmc.em.domain.shop

import net.badgersmc.em.domain.stall.StallId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShopSignTest {
    @Test fun `a sell sign carries the item, price, and container location`() {
        val sign = ShopSign(
            id = 0L,
            stallId = StallId("stall_01"),
            direction = SignDirection.SELL,
            itemKey = "minecraft:diamond",
            price = 5L,
            signLocation = "world:100:64:200",
            containerLocation = "world:100:65:200"
        )
        assertEquals(SignDirection.SELL, sign.direction)
        assertEquals(5L, sign.price)
    }

    @Test fun `price must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ShopSign(
                id = 0L,
                stallId = StallId("stall_01"),
                direction = SignDirection.BUY,
                itemKey = "minecraft:diamond",
                price = 0L,
                signLocation = "world:0:0:0",
                containerLocation = "world:0:1:0"
            )
        }
    }
}
