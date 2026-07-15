package net.badgersmc.em.interaction.bedrock

import io.mockk.mockk
import net.badgersmc.em.domain.shop.ShopRepository
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * TDD-60: Tests for BedrockCreateShopForm — Cumulus CustomForm for creating shops.
 */
class BedrockCreateShopFormTest {

    private val stallOwner = UUID.randomUUID()
    private val stallId = "stall_01"

    @Test
    fun `create shop form constructs without throwing`() {
        val signLoc = mockk<Location>(relaxed = true)
        val containerLoc = mockk<Location>(relaxed = true)

        val form = BedrockCreateShopForm(
            mockk<Player>(relaxed = true),
            stallOwner,
            stallId,
            signLoc,
            containerLoc,
            "dummyBase64",
            mockk<ShopRepository>(relaxed = true),
            mockk<Logger>(relaxed = true),
            mockk(relaxed = true),
            mockk<net.badgersmc.em.application.ShopSignRenderer>(relaxed = true),
        )
        assertNotNull(form)
    }

    @Test
    fun `create shop form builds form without throwing`() {
        val signLoc = mockk<Location>(relaxed = true)
        val containerLoc = mockk<Location>(relaxed = true)

        val form = BedrockCreateShopForm(
            mockk<Player>(relaxed = true),
            stallOwner,
            stallId,
            signLoc,
            containerLoc,
            "dummyBase64",
            mockk<ShopRepository>(relaxed = true),
            mockk<Logger>(relaxed = true),
            mockk(relaxed = true),
            mockk<net.badgersmc.em.application.ShopSignRenderer>(relaxed = true),
        )
        val built = form.buildForm()
        assertNotNull(built)
    }
}