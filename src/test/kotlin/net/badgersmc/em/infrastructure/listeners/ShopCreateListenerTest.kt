package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.block.data.type.WallSign
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.test.Test

class ShopCreateListenerTest {

    private val testUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val worldName = "world"

    /** Build a mocked wall-sign block that returns [signBlock] and is attached to [containerBlock] via its facing. */
    private fun wallSignBlock(
        signBlock: Block,
        containerBlock: Block,
        facing: BlockFace = BlockFace.NORTH
    ): Block {
        val wallSign: Sign = mockk(relaxed = true)
        every { signBlock.state } returns wallSign
        every { signBlock.type } returns Material.OAK_WALL_SIGN

        val wallData: WallSign = mockk(relaxed = true)
        every { wallData.facing } returns facing
        every { signBlock.blockData } returns wallData

        every { signBlock.getRelative(facing.oppositeFace) } returns containerBlock
        return signBlock
    }

    /** Create a mock Location at a fixed position. */
    private fun location(x: Int = 100, y: Int = 64, z: Int = 200): Location {
        val loc: Location = mockk(relaxed = true)
        every { loc.world?.name } returns worldName
        every { loc.blockX } returns x
        every { loc.blockY } returns y
        every { loc.blockZ } returns z
        return loc
    }

    /** Create a mocked container block. */
    private fun containerBlock(): Block {
        val block: Block = mockk(relaxed = true)
        val container: Container = mockk(relaxed = true)
        every { block.state } returns container
        return block
    }

    /** Create a mocked non-container block. */
    private fun nonContainerBlock(): Block {
        val block: Block = mockk(relaxed = true)
        val state: org.bukkit.block.BlockState = mockk(relaxed = true)
        every { block.state } returns state
        return block
    }

    /** Create a sample owned stall for the test player. */
    private fun sampleStall(ownerUuid: UUID = testUuid): Stall = Stall(
        id = StallId("stall_01"),
        regionId = "stall_01",
        world = worldName,
        state = StallState.OWNED,
        owner = OwnerRef.solo(ownerUuid),
        ownerSince = null,
        winningBid = 1000L,
        rentTerms = RentTerms.formula(0.01)
    )

    /** Create a listener whose findStallAt returns the given stall. */
    private fun listenerWithStall(
        stallRepo: StallRepository = mockk(relaxed = true),
        shopRepo: ShopRepository = mockk(relaxed = true),
        stall: Stall? = sampleStall()
    ): ShopCreateListener {
        val listener = ShopCreateListener(stallRepo, shopRepo)
        return object : ShopCreateListener(stallRepo, shopRepo) {
            override fun findStallAt(location: Location): Stall? = stall
            override fun canManageStall(stall: Stall, player: Player): Boolean = true
        }
    }

    /** Helper: create a PlayerInteractEvent. */
    private fun interactEvent(
        player: Player,
        action: Action = Action.LEFT_CLICK_BLOCK,
        block: Block
    ): PlayerInteractEvent {
        val loc = block.location ?: location()
        every { block.location } returns loc
        return PlayerInteractEvent(player, action, null as ItemStack?, block, BlockFace.NORTH, EquipmentSlot.HAND)
    }

    // ===== Primary success case =====

    @Test
    fun `left-click sneaking on wall sign attached to container inside owned stall cancels event`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns true

        val signBlock: Block = mockk(relaxed = true)
        val loc = location()
        every { signBlock.location } returns loc
        val contBlock = containerBlock()
        wallSignBlock(signBlock, contBlock)

        val shopRepo = mockk<ShopRepository>(relaxed = true)
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns null

        val listener = listenerWithStall(shopRepo = shopRepo)

        val event = interactEvent(player, block = signBlock)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() == Event.Result.DENY) { "Event should be cancelled" }
        verify { player.sendMessage("§e[Shop] Create menu would open here (TDD-52)") }
    }

    // ===== Negative cases =====

    @Test
    fun `non-sneaking left-click does not cancel event`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns false

        val signBlock: Block = mockk(relaxed = true)
        val loc = location()
        every { signBlock.location } returns loc
        val contBlock = containerBlock()
        wallSignBlock(signBlock, contBlock)

        val listener = listenerWithStall()
        val event = interactEvent(player, block = signBlock)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() != Event.Result.DENY) { "Event should not be cancelled when not sneaking" }
    }

    @Test
    fun `right-click does not cancel event`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns true

        val signBlock: Block = mockk(relaxed = true)
        val loc = location()
        every { signBlock.location } returns loc
        val contBlock = containerBlock()
        wallSignBlock(signBlock, contBlock)

        val listener = listenerWithStall()
        val event = interactEvent(player, action = Action.RIGHT_CLICK_BLOCK, block = signBlock)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() != Event.Result.DENY) { "Event should not be cancelled on right-click" }
    }

    @Test
    fun `sign already registered as shop shows already-a-shop message`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns true

        val signBlock: Block = mockk(relaxed = true)
        val loc = location()
        every { signBlock.location } returns loc
        val contBlock = containerBlock()
        wallSignBlock(signBlock, contBlock)

        val shopRepo = mockk<ShopRepository>(relaxed = true)
        // Simulate an existing shop at this sign location
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns mockk(relaxed = true)

        val listener = listenerWithStall(shopRepo = shopRepo)
        val event = interactEvent(player, block = signBlock)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() != Event.Result.DENY) { "Event should not be cancelled when shop already exists" }
        verify { player.sendMessage("§cThis sign is already a shop") }
    }

    @Test
    fun `sign not attached to a container shows attachment message`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns true

        val signBlock: Block = mockk(relaxed = true)
        val loc = location()
        every { signBlock.location } returns loc
        val nonContBlock = nonContainerBlock()
        wallSignBlock(signBlock, nonContBlock)

        val shopRepo = mockk<ShopRepository>(relaxed = true)
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns null

        val listener = listenerWithStall(shopRepo = shopRepo)
        val event = interactEvent(player, block = signBlock)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() != Event.Result.DENY) { "Event should not be cancelled when sign not on container" }
        verify { player.sendMessage("§cSign must be attached to a container (chest, barrel, shulker)") }
    }

    @Test
    fun `sign outside owned stall shows stall ownership message`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns true

        val signBlock: Block = mockk(relaxed = true)
        val loc = location()
        every { signBlock.location } returns loc
        val contBlock = containerBlock()
        wallSignBlock(signBlock, contBlock)

        val shopRepo = mockk<ShopRepository>(relaxed = true)
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns null

        // Listener that finds no stall
        val listener = listenerWithStall(stall = null, shopRepo = shopRepo)
        val event = interactEvent(player, block = signBlock)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() != Event.Result.DENY) { "Event should not be cancelled outside a stall" }
        verify { player.sendMessage("§cSign must be inside a registered stall") }
    }

    @Test
    fun `non-wall-sign block does not cancel event`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns true

        // A regular standing sign, not a wall sign
        val block: Block = mockk(relaxed = true)
        val signState: Sign = mockk(relaxed = true)
        every { block.state } returns signState
        // blockData is NOT WallSign — it's a regular Sign block data
        every { block.blockData } returns mockk<org.bukkit.block.data.type.Sign>(relaxed = true)
        val loc = location()
        every { block.location } returns loc

        val listener = listenerWithStall()
        val event = interactEvent(player, block = block)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() != Event.Result.DENY) { "Event should not be cancelled for non-wall-sign blocks" }
    }

    @Test
    fun `player without stall management permission gets denied message`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID() // different from stall owner
        every { player.isSneaking } returns true
        every { player.hasPermission("enthusiamarket.admin") } returns false

        val signBlock: Block = mockk(relaxed = true)
        val loc = location()
        every { signBlock.location } returns loc
        val contBlock = containerBlock()
        wallSignBlock(signBlock, contBlock)

        val shopRepo = mockk<ShopRepository>(relaxed = true)
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns null

        val stall = sampleStall()
        val stallRepo = mockk<StallRepository>(relaxed = true)
        val listener = object : ShopCreateListener(stallRepo, shopRepo) {
            override fun findStallAt(location: Location): Stall? = stall
            override fun canManageStall(stall: Stall, player: Player): Boolean = false
        }

        val event = interactEvent(player, block = signBlock)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() != Event.Result.DENY) { "Event should not be cancelled when player cannot manage stall" }
        verify { player.sendMessage("§cYou do not own or rent this stall") }
    }
}