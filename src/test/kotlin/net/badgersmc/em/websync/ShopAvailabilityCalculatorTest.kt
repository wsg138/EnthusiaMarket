package net.badgersmc.em.websync

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallState
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopAvailabilityCalculatorTest {
    private val owner = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val economy = mockk<EconomyProvider>()
    private val guilds = mockk<GuildProvider>()
    private val inventory = mockk<Inventory>()
    private val stone = mockk<ItemStack> { every { maxStackSize } returns 64 }

    @Test
    fun `BUY uses owner funding and accepting capacity instead of current stock`() {
        every { economy.balance(owner) } returns 100
        every { inventory.storageContents } returns arrayOfNulls(10)
        every { inventory.maxStackSize } returns 64
        val calculator = ShopAvailabilityCalculator(economy, guilds, LoadedContainerAccess { inventory }) { stone }
        assertEquals(10, calculator.availableTrades(shop(SignDirection.BUY, stock = 999, sellAmount = 2, cost = 10), stall()))
    }

    @Test
    fun `SELL and TRADE use persisted matching stock`() {
        val calculator = ShopAvailabilityCalculator(economy, guilds, LoadedContainerAccess { null }) { stone }
        assertEquals(4, calculator.availableTrades(shop(SignDirection.SELL, 9, 2), stall()))
        assertEquals(3, calculator.availableTrades(shop(SignDirection.TRADE, 9, 3), stall()))
    }

    @Test
    fun `frozen overdue and unloaded BUY shops report zero`() {
        every { economy.balance(owner) } returns 1000
        val calculator = ShopAvailabilityCalculator(economy, guilds, LoadedContainerAccess { null }) { stone }
        assertEquals(0, calculator.availableTrades(shop(SignDirection.BUY, frozen = true), stall()))
        assertEquals(0, calculator.availableTrades(shop(SignDirection.SELL, stock = 100), stall(StallState.GRACE)))
        assertEquals(0, calculator.availableTrades(shop(SignDirection.BUY), stall()))
    }

    private fun stall(state: StallState = StallState.OWNED) = Stall(
        StallId("stall1"), "stall1", "world", state, OwnerRef.solo(owner), null, 1,
        RentTerms.flat(1),
    )

    private fun shop(
        direction: SignDirection,
        stock: Int = 0,
        sellAmount: Int = 1,
        cost: Int = 10,
        frozen: Boolean = false,
    ) = Shop(
        id = 1, stallId = "stall1", owner = owner,
        signWorld = "world", signX = 0, signY = 64, signZ = 0,
        containerWorld = "world", containerX = 0, containerY = 64, containerZ = 1,
        sellItem = "unused", sellAmount = sellAmount, costItem = "unused", costAmount = cost,
        frozen = frozen, direction = direction, stockCount = stock,
    )
}
