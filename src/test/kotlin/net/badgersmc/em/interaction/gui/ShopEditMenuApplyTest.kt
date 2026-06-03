package net.badgersmc.em.interaction.gui

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopEditMenuApplyTest {

    private fun shop() = Shop(
        id = 1, stallId = "stall1", owner = UUID.randomUUID(),
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "old", sellAmount = 1, costItem = "c", costAmount = 10,
        hopperAllowIn = true, hopperAllowOut = true, frozen = false,
        direction = SignDirection.SELL,
    )

    @Test fun `applyEdits returns a copy with the edited fields`() {
        val updated = ShopEditMenu.applyEdits(
            shop(), sellItemB64 = "new", sellAmount = 5, costAmount = 99,
            hopperIn = false, hopperOut = false, frozen = true,
        )
        assertEquals("new", updated.sellItem)
        assertEquals(5, updated.sellAmount)
        assertEquals(99, updated.costAmount)
        assertEquals(false, updated.hopperAllowIn)
        assertEquals(false, updated.hopperAllowOut)
        assertEquals(true, updated.frozen)
    }

    @Test fun `applyEdits clamps amounts to at least one`() {
        val updated = ShopEditMenu.applyEdits(
            shop(), sellItemB64 = "x", sellAmount = 0, costAmount = -5,
            hopperIn = true, hopperOut = true, frozen = false,
        )
        assertEquals(1, updated.sellAmount)
        assertEquals(1, updated.costAmount)
    }
}