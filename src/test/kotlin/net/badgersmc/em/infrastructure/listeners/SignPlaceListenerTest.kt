package net.badgersmc.em.infrastructure.listeners

import io.mockk.*
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.block.data.type.WallSign
import org.bukkit.entity.Player
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Simplified shop creation: player places wall sign on container, GUI opens
 * with the sell item auto-detected from the container. Tests verify the event
 * is cancelled (GUI path engaged), container is scanned, and the correct item
 * is passed to openCreateGui.
 */
class SignPlaceListenerTest {

    private val placerUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val guildId = "22222222-2222-2222-2222-222222222222"
    private val worldName = "world"
    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        mockkObject(ItemStackSerializer)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
        unmockkAll()
    }

    /** Creates sign + container blocks. Container has [contents] in its inventory. */
    private fun signAndContainer(contents: Array<ItemStack?>): Pair<Block, Block> {
        val signBlock: Block = mockk(relaxed = true)
        val wallSign: Sign = mockk(relaxed = true)
        every { signBlock.state } returns wallSign
        every { signBlock.type } returns Material.OAK_WALL_SIGN

        val wallData: WallSign = mockk(relaxed = true)
        every { wallData.facing } returns BlockFace.NORTH
        every { signBlock.blockData } returns wallData

        val loc: Location = mockk(relaxed = true)
        every { loc.world?.name } returns worldName
        every { loc.blockX } returns 100
        every { loc.blockY } returns 64
        every { loc.blockZ } returns 200
        every { signBlock.location } returns loc

        val containerInv: Inventory = mockk(relaxed = true)
        every { containerInv.contents } returns contents

        val containerLoc: Location = mockk(relaxed = true)
        val containerBlock: Block = mockk(relaxed = true)
        val container: Container = mockk(relaxed = true)
        every { container.inventory } returns containerInv
        every { containerBlock.state } returns container
        every { containerBlock.location } returns containerLoc
        every { containerBlock.world.name } returns worldName
        every { containerBlock.x } returns 101
        every { containerBlock.y } returns 64
        every { containerBlock.z } returns 201
        every { signBlock.getRelative(BlockFace.SOUTH) } returns containerBlock
        return signBlock to containerBlock
    }

    private fun placerPlayer(): Player {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns placerUuid
        every { player.hasPermission("enthusiamarket.shop.create") } returns true
        return player
    }

    private fun guildStall(): Stall = Stall(
        id = StallId("stall_01"), regionId = "stall_01", world = worldName,
        state = StallState.OWNED, owner = OwnerRef.guild(guildId),
        ownerSince = null, winningBid = 1000L, rentTerms = RentTerms.formula(0.01),
    )

    private fun soloStall(): Stall = Stall(
        id = StallId("stall_01"), regionId = "stall_01", world = worldName,
        state = StallState.OWNED, owner = OwnerRef.solo(placerUuid),
        ownerSince = null, winningBid = 1000L, rentTerms = RentTerms.formula(0.01),
    )

    private fun lenientLang(): LangService {
        val lang = mockk<LangService>(relaxed = true)
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        return lang
    }

    /** Captures the [ShopCreateParams] passed to openCreateGui. */
    private fun listener(stall: Stall?, shopRepo: ShopRepository, capture: MutableList<ShopCreateParams>): SignPlaceListener {
        return object : SignPlaceListener(
            mockk<StallRepository>(relaxed = true), shopRepo,
            mockk<GuildProvider>(relaxed = true), lenientLang(),
            EnthusiaMarketConfig(),
        ) {
            override fun findStallAt(location: Location): Stall? = stall
            override fun canManageStall(stall: Stall, player: Player): Boolean = true
            override fun openCreateGui(params: ShopCreateParams, player: Player) {
                capture.add(params)
            }
        }
    }

    private fun signChangeEvent(player: Player, signBlock: Block): SignChangeEvent {
        val event: SignChangeEvent = mockk(relaxed = true)
        every { event.player } returns player
        every { event.block } returns signBlock
        return event
    }

    @Test
    fun `places sign on container with items opens GUI with sell item from container`() {
        val diamond = ItemStack(Material.DIAMOND, 1)
        every { ItemStackSerializer.serialize(any()) } returns "base64_diamond"

        val (signBlock, _) = signAndContainer(arrayOf(diamond))
        val player = placerPlayer()
        val shopRepo = mockk<ShopRepository>(relaxed = true)
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns null

        val paramsCapture = mutableListOf<ShopCreateParams>()
        val sut = listener(stall = soloStall(), shopRepo = shopRepo, capture = paramsCapture)
        val event = signChangeEvent(player, signBlock)

        sut.onSignPlace(event)

        verify { event.isCancelled = true }
        verify(exactly = 0) { shopRepo.upsert(any()) }

        val params = paramsCapture.single()
        assertEquals("stall_01", params.stallId)
        assertEquals(placerUuid, params.owner)
        assertEquals("base64_diamond", params.sellItemB64)
    }

    @Test
    fun `empty container shows error and cancels event`() {
        val (signBlock, _) = signAndContainer(arrayOf())
        val player = placerPlayer()
        val shopRepo = mockk<ShopRepository>(relaxed = true)
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns null

        val paramsCapture = mutableListOf<ShopCreateParams>()
        val sut = listener(stall = soloStall(), shopRepo = shopRepo, capture = paramsCapture)
        val event = signChangeEvent(player, signBlock)

        sut.onSignPlace(event)

        verify { event.isCancelled = true }
        // GUI must NOT be opened (container is empty)
        assertTrue(paramsCapture.isEmpty())
    }

    @Test
    fun `GUILD stall opens GUI with guildId set`() {
        val diamond = ItemStack(Material.DIAMOND, 1)
        every { ItemStackSerializer.serialize(any()) } returns "base64_diamond"

        val (signBlock, _) = signAndContainer(arrayOf(diamond))
        val player = placerPlayer()
        val shopRepo = mockk<ShopRepository>(relaxed = true)
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns null

        val paramsCapture = mutableListOf<ShopCreateParams>()
        val sut = listener(stall = guildStall(), shopRepo = shopRepo, capture = paramsCapture)
        val event = signChangeEvent(player, signBlock)

        sut.onSignPlace(event)

        verify { event.isCancelled = true }
        val params = paramsCapture.single()
        assertTrue(params.guildId == guildId)
    }
}
