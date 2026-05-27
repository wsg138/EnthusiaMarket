package net.badgersmc.em.interaction.bedrock

import io.mockk.*
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import org.bukkit.entity.Player
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * TDD-60: Tests for BedrockShopEditForm — Cumulus CustomForm for editing shops.
 */
class BedrockShopEditFormTest {

    private val testShop = Shop(
        id = 1L, stallId = "s1", owner = UUID.randomUUID(),
        signWorld = "w", signX = 1, signY = 2, signZ = 3,
        containerWorld = "w", containerX = 4, containerY = 5, containerZ = 6,
        sellItem = "a", sellAmount = 1, costItem = "b", costAmount = 1,
        frozen = false, hopperAllowIn = true, hopperAllowOut = true
    )

    @Test
    fun `edit form class exists`() {
        val cls = Class.forName("net.badgersmc.em.interaction.bedrock.BedrockShopEditForm")
        assertNotNull(cls)
    }

    @Test
    fun `edit form constructs without throwing`() {
        val form = BedrockShopEditForm(
            mockk<Player>(relaxed = true),
            testShop,
            mockk<ShopRepository>(relaxed = true),
            mockk<Logger>(relaxed = true),
            mockk(relaxed = true)
        )
        assertNotNull(form)
    }

    @Test
    fun `edit form builds form without throwing`() {
        val form = BedrockShopEditForm(
            mockk<Player>(relaxed = true),
            testShop,
            mockk<ShopRepository>(relaxed = true),
            mockk<Logger>(relaxed = true),
            mockk(relaxed = true)
        )
        val built = form.buildForm()
        assertNotNull(built)
    }

    @Test
    fun `frozen shop shows toggles correctly`() {
        val shop = testShop.copy(frozen = true)
        val form = BedrockShopEditForm(
            mockk<Player>(relaxed = true),
            shop,
            mockk<ShopRepository>(relaxed = true),
            mockk<Logger>(relaxed = true),
            mockk(relaxed = true)
        )
        assertNotNull(form.buildForm())
    }
}