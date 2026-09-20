package net.badgersmc.em.interaction.bedrock

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopSignRenderer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.impl.FormImpl
import org.geysermc.cumulus.response.CustomFormResponse
import org.geysermc.cumulus.response.result.FormResponseResult
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * TDD-60: Tests for BedrockCreateShopForm — Cumulus CustomForm for creating shops.
 */
class BedrockCreateShopFormTest {

    private val stallOwner = UUID.randomUUID()
    private val stallId = "stall_01"

    @BeforeTest
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterTest
    fun tearDown() {
        MockBukkit.unmock()
    }

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

    @Test
    fun `currency form directions persist their entered price`() {
        listOf(
            0 to SignDirection.SELL,
            1 to SignDirection.BUY,
        ).forEach { (directionIndex, direction) ->
            val fixture = fixture()
            val captured = slot<Shop>()

            submit(fixture.form, response(directionIndex, "100", "3"))

            verify(exactly = 1) { fixture.repository.upsert(capture(captured)) }
            assertEquals(direction, captured.captured.direction)
            assertEquals(100, captured.captured.costAmount)
            assertEquals(3, captured.captured.sellAmount)
            assertEquals(Material.RAW_GOLD, ItemStackSerializer.deserialize(captured.captured.costItem)?.type)
        }
    }

    @Test
    fun `currency form retains the factory price clamp`() {
        val fixture = fixture()
        val captured = slot<Shop>()

        submit(fixture.form, response(0, Long.MAX_VALUE.toString(), "1"))

        verify(exactly = 1) { fixture.repository.upsert(capture(captured)) }
        assertEquals(Int.MAX_VALUE, captured.captured.costAmount)
    }

    @Test
    fun `currency form defaults missing and malformed amount to one`() {
        listOf<String?>(null, "not-a-number").forEach { amountText ->
            val fixture = fixture()
            val captured = slot<Shop>()

            submit(fixture.form, response(0, "100", amountText))

            verify(exactly = 1) { fixture.repository.upsert(capture(captured)) }
            assertEquals(1, captured.captured.sellAmount)
        }
    }

    @Test
    fun `currency form rejects a nonpositive price without persistence`() {
        val fixture = fixture()

        submit(fixture.form, response(0, "0", "1"))

        verify(exactly = 0) { fixture.repository.upsert(any()) }
    }

    @Test
    fun `form rejects a nonpositive amount with the invalid input message`() {
        val fixture = fixture()
        every { fixture.lang.legacy("shop.create.invalid_input") } returns "invalid input"

        submit(fixture.form, response(0, "100", "0"))

        verify(exactly = 0) { fixture.repository.upsert(any()) }
        verify(exactly = 1) { fixture.player.sendMessage("invalid input") }
    }

    @Test
    fun `trade form retains its item cost amount`() {
        val fixture = fixture()
        val captured = slot<Shop>()

        submit(fixture.form, response(2, "2 diamond", "1"))

        verify(exactly = 1) { fixture.repository.upsert(capture(captured)) }
        assertEquals(SignDirection.TRADE, captured.captured.direction)
        assertEquals(2, captured.captured.costAmount)
        assertEquals(Material.DIAMOND, ItemStackSerializer.deserialize(captured.captured.costItem)?.type)
    }

    @Test
    fun `trade form rejects invalid trade cost with its dedicated message`() {
        val fixture = fixture()
        every { fixture.lang.legacy("shop.create.invalid_trade_cost") } returns "invalid trade cost"

        submit(fixture.form, response(2, "not-a-trade", "1"))

        verify(exactly = 0) { fixture.repository.upsert(any()) }
        verify(exactly = 1) { fixture.player.sendMessage("invalid trade cost") }
    }

    private fun fixture(): FormFixture {
        val repository = mockk<ShopRepository>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val lang = mockk<LangService>(relaxed = true)
        val form = BedrockCreateShopForm(
            player,
            stallOwner,
            stallId,
            location(),
            location(),
            "dummyBase64",
            repository,
            mockk<Logger>(relaxed = true),
            lang,
            mockk<ShopSignRenderer>(relaxed = true),
        )
        return FormFixture(form, repository, player, lang)
    }

    private fun location(): Location {
        val location = mockk<Location>()
        val block = mockk<Block>()
        every { location.world } returns null
        every { location.blockX } returns 1
        every { location.blockY } returns 64
        every { location.blockZ } returns 1
        every { location.block } returns block
        every { block.state } returns mockk<BlockState>()
        return location
    }

    private fun response(direction: Int, price: String?, amount: String?): CustomFormResponse = mockk {
        every { asDropdown(1) } returns direction
        every { asInput(2) } returns price
        every { asInput(3) } returns amount
    }

    @Suppress("UNCHECKED_CAST")
    private fun submit(form: BedrockCreateShopForm, response: CustomFormResponse) {
        val built = form.buildForm() as FormImpl<CustomFormResponse>
        built.callResultHandler(FormResponseResult.valid(response))
    }

    private data class FormFixture(
        val form: BedrockCreateShopForm,
        val repository: ShopRepository,
        val player: Player,
        val lang: LangService,
    )
}
