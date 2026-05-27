package net.badgersmc.em.interaction.bedrock

import io.mockk.*
import net.badgersmc.em.domain.shop.Shop
import org.bukkit.entity.Player
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * TDD-60: Tests for BedrockPurchaseForm — Cumulus SimpleForm for shop purchases.
 */
class BedrockPurchaseFormTest {

    private val testShop = Shop(
        id = 1L, stallId = "s1", owner = UUID.randomUUID(),
        signWorld = "w", signX = 1, signY = 2, signZ = 3,
        containerWorld = "w", containerX = 4, containerY = 5, containerZ = 6,
        sellItem = "diamond", sellAmount = 1, costItem = "10", costAmount = 10
    )

    @Test
    fun `purchase form constructs without throwing`() {
        val form = BedrockPurchaseForm(
            mockk<Player>(relaxed = true),
            testShop,
            {},
            {},
            mockk<Logger>(relaxed = true),
            mockk(relaxed = true)
        )
        assertNotNull(form)
    }
}
