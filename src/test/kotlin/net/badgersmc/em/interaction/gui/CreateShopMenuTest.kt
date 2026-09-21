package net.badgersmc.em.interaction.gui

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopSignRenderer
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.events.ShopCreatedEvent
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.block.sign.SignSide
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateShopMenuTest {

    private val stallOwner = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        if (MockBukkit.isMocked()) MockBukkit.unmock()
    }

    @Test
    fun `successful confirmation persists and publishes the created shop event`() {
        val fixture = fixture()
        val eventSlot = slot<Event>()
        val pluginManager = mockk<PluginManager>(relaxed = true)
        mockkStatic(Bukkit::class)
        every { pluginManager.callEvent(capture(eventSlot)) } answers { }
        every { Bukkit.getPluginManager() } returns pluginManager

        assertTrue(fixture.menu.confirmCreation(fixture.player))

        verifyOrder {
            fixture.sign.update(true, false)
            fixture.repository.upsert(any())
            pluginManager.callEvent(any<ShopCreatedEvent>())
        }
        kotlin.test.assertEquals(stallOwner, (eventSlot.captured as ShopCreatedEvent).ownerId)
    }

    @Test
    fun `failed sign write does not persist or publish a shop`() {
        val fixture = fixture(signUpdateSucceeds = false)

        assertFalse(fixture.menu.confirmCreation(fixture.player))

        verify(exactly = 1) { fixture.sign.update(true, false) }
        verify(exactly = 0) { fixture.repository.upsert(any()) }
        verify(exactly = 1) { fixture.player.sendMessage(Component.empty()) }
    }

    private fun fixture(signUpdateSucceeds: Boolean = true): MenuFixture {
        val sign = mockk<Sign>(relaxed = true)
        every { sign.getSide(Side.FRONT) } returns mockk<SignSide>(relaxed = true)
        every { sign.update(true, false) } returns signUpdateSucceeds
        val world = mockk<World>(relaxed = true)
        every { world.name } returns "world"
        val signBlock = mockk<Block>(relaxed = true)
        every { signBlock.state } returns sign
        every { world.getBlockAt(1, 64, 1) } returns signBlock

        val signLocation = mockk<Location>(relaxed = true)
        every { signLocation.world } returns world
        every { signLocation.blockX } returns 1
        every { signLocation.blockY } returns 64
        every { signLocation.blockZ } returns 1

        val containerLocation = mockk<Location>(relaxed = true)
        every { containerLocation.world } returns world
        every { containerLocation.blockX } returns 2
        every { containerLocation.blockY } returns 64
        every { containerLocation.blockZ } returns 1

        val repository = mockk<ShopRepository>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val lang = mockk<LangService>()
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        val menu = CreateShopMenu(
            stallId = "stall_01",
            stallOwner = stallOwner,
            signLoc = signLocation,
            containerLoc = containerLocation,
            sellItemBase64 = ItemStackSerializer.serialize(org.bukkit.inventory.ItemStack(Material.DIAMOND)),
            shopRepository = repository,
            lang = lang,
            signRenderer = ShopSignRenderer(),
        )
        return MenuFixture(menu, repository, player, sign)
    }

    private data class MenuFixture(
        val menu: CreateShopMenu,
        val repository: ShopRepository,
        val player: Player,
        val sign: Sign,
    )
}
