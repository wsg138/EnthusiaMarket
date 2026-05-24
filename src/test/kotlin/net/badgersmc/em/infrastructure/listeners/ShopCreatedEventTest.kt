package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.events.ShopCreatedEvent
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.block.data.type.WallSign
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID

class ShopCreatedEventTest {

    private lateinit var server: ServerMock
    private val testUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val worldName = "world"

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `shop creation fires ShopCreatedEvent with correct owner UUID`() {
        // ── Given: a player left-click-sneaking on a wall sign inside an owned stall ──
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns true

        val loc = mockk<Location>(relaxed = true)
        every { loc.world?.name } returns worldName
        every { loc.blockX } returns 100
        every { loc.blockY } returns 64
        every { loc.blockZ } returns 200

        val signBlock: Block = mockk(relaxed = true)
        every { signBlock.location } returns loc

        val wallSign: Sign = mockk(relaxed = true)
        every { signBlock.state } returns wallSign
        every { signBlock.type } returns Material.OAK_WALL_SIGN
        val wallData: WallSign = mockk(relaxed = true)
        every { wallData.facing } returns BlockFace.NORTH
        every { signBlock.blockData } returns wallData

        val containerBlock: Block = mockk(relaxed = true)
        val container: Container = mockk(relaxed = true)
        every { containerBlock.state } returns container
        every { signBlock.getRelative(BlockFace.SOUTH) } returns containerBlock

        val shopRepo = mockk<ShopRepository>(relaxed = true)
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns null

        val stallRepo = mockk<StallRepository>(relaxed = true)
        val stall = Stall(
            id = StallId("stall_01"),
            regionId = "stall_01",
            world = worldName,
            state = StallState.OWNED,
            owner = OwnerRef.solo(testUuid),
            ownerSince = null,
            winningBid = 1000L,
            rentTerms = RentTerms.formula(0.01)
        )

        val listener = object : ShopCreateListener(stallRepo, shopRepo) {
            override fun findStallAt(location: Location): Stall? = stall
            override fun canManageStall(stall: Stall, player: Player): Boolean = true
        }

        // ── When: the sign is interacted with ──
        val event = PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK, null as ItemStack?, signBlock, BlockFace.NORTH, EquipmentSlot.HAND)
        listener.onSignInteract(event)

        // ── Then: ShopCreatedEvent should have been fired with the player's UUID ──
        server.pluginManager.assertEventFired(ShopCreatedEvent::class.java) { e ->
            e.ownerId == testUuid
        }
    }
}