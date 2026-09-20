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
import net.badgersmc.em.interaction.MenuFactory
import net.badgersmc.nexus.i18n.LangService
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
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import kotlin.test.Test
import net.kyori.adventure.text.Component

class ShopCreateListenerTest {

    private val testUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val worldName = "world"
    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

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

    private class RecordingShopCreateListener(
        stallRepository: StallRepository,
        shopRepository: ShopRepository,
        lang: LangService,
        menuFactory: MenuFactory,
        private val foundStall: Stall?,
        private val managesStall: Boolean,
    ) : ShopCreateListener(
        stallRepository,
        shopRepository,
        lang,
        menuFactory,
        mockk<net.badgersmc.em.application.ShopSignRenderer>(relaxed = true),
        mockk(relaxed = true),
    ) {
        var javaMenuOpened = false
        var bedrockFormOpened = false

        override fun findStallAt(location: Location): Stall? = foundStall

        override fun canManageStall(stall: Stall, player: Player): Boolean = managesStall

        protected override fun openCreateShopMenu(
            player: Player,
            stall: Stall,
            signLocation: Location,
            containerLocation: Location,
            sellItemB64: String,
        ) {
            javaMenuOpened = true
        }

        protected override fun openBedrockCreateShopForm(
            player: Player,
            stall: Stall,
            signLocation: Location,
            containerLocation: Location,
            sellItemB64: String,
        ) {
            bedrockFormOpened = true
        }
    }

    /** Create a listener whose findStallAt returns the given stall. */
    private fun listenerWithStall(
        stallRepo: StallRepository = mockk(relaxed = true),
        shopRepo: ShopRepository = mockk(relaxed = true),
        stall: Stall? = sampleStall(),
        lang: LangService = mockk(relaxed = true),
        menuFactory: MenuFactory = mockk(relaxed = true),
        managesStall: Boolean = true,
    ): RecordingShopCreateListener = RecordingShopCreateListener(
        stallRepo, shopRepo, lang, menuFactory, stall, managesStall,
    )

    private fun setHeldItem(player: Player, item: ItemStack) {
        val inventory: PlayerInventory = mockk(relaxed = true)
        every { player.inventory } returns inventory
        every { inventory.itemInMainHand } returns item
    }

    /** Helper: create a PlayerInteractEvent. */
    private fun interactEvent(
        player: Player,
        action: Action = Action.LEFT_CLICK_BLOCK,
        block: Block?,
        hand: EquipmentSlot = EquipmentSlot.HAND,
    ): PlayerInteractEvent {
        val loc = block?.location ?: location()
        if (block != null) every { block.location } returns loc
        return PlayerInteractEvent(player, action, null as ItemStack?, block, BlockFace.NORTH, hand)
    }

    // ===== Valid creation paths =====

    @Test
    fun `empty hand denies block use and shows held item message`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns true
        setHeldItem(player, ItemStack(Material.AIR))

        val signBlock: Block = mockk(relaxed = true)
        val loc = location()
        every { signBlock.location } returns loc
        val contBlock = containerBlock()
        wallSignBlock(signBlock, contBlock)

        val shopRepo = mockk<ShopRepository>(relaxed = true)
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns null
        val lang: LangService = mockk(relaxed = true)
        every { lang.msg("shop.create.no_held_item") } returns Component.empty()

        val listener = listenerWithStall(shopRepo = shopRepo, lang = lang)

        val event = interactEvent(player, block = signBlock)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() == Event.Result.DENY) { "Event should deny block use" }
        verify(exactly = 1) { lang.msg("shop.create.no_held_item") }
        assert(!listener.javaMenuOpened) { "Empty hand should not open the Java menu" }
        assert(!listener.bedrockFormOpened) { "Empty hand should not open the Bedrock form" }
    }

    @Test
    fun `eligible main-hand interaction opens Java create menu`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns true
        setHeldItem(player, ItemStack(Material.DIAMOND, 16))

        val signBlock: Block = mockk(relaxed = true)
        val loc = location()
        every { signBlock.location } returns loc
        wallSignBlock(signBlock, containerBlock())

        val shopRepo = mockk<ShopRepository>(relaxed = true)
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns null
        val menuFactory: MenuFactory = mockk(relaxed = true)
        every { menuFactory.shouldUseBedrockMenus(player) } returns false
        val listener = listenerWithStall(shopRepo = shopRepo, menuFactory = menuFactory)

        val event = interactEvent(player, block = signBlock)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() == Event.Result.DENY) { "Event should deny block use" }
        assert(listener.javaMenuOpened) { "Eligible Java player should open the create menu" }
        assert(!listener.bedrockFormOpened) { "Java player should not open the Bedrock form" }
    }

    @Test
    fun `eligible main-hand interaction opens Bedrock create form`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns true
        setHeldItem(player, ItemStack(Material.DIAMOND, 16))

        val signBlock: Block = mockk(relaxed = true)
        val loc = location()
        every { signBlock.location } returns loc
        wallSignBlock(signBlock, containerBlock())

        val shopRepo = mockk<ShopRepository>(relaxed = true)
        every { shopRepo.findBySign(worldName, 100, 64, 200) } returns null
        val menuFactory: MenuFactory = mockk(relaxed = true)
        every { menuFactory.shouldUseBedrockMenus(player) } returns true
        val listener = listenerWithStall(shopRepo = shopRepo, menuFactory = menuFactory)

        val event = interactEvent(player, block = signBlock)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() == Event.Result.DENY) { "Event should deny block use" }
        assert(!listener.javaMenuOpened) { "Bedrock player should not open the Java menu" }
        assert(listener.bedrockFormOpened) { "Eligible Bedrock player should open the create form" }
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
    fun `off-hand left-click does not open a second create flow`() {
        val player: Player = mockk(relaxed = true)
        every { player.uniqueId } returns testUuid
        every { player.isSneaking } returns true

        val signBlock: Block = mockk(relaxed = true)
        val loc = location()
        every { signBlock.location } returns loc
        wallSignBlock(signBlock, containerBlock())

        val listener = listenerWithStall()
        val event = interactEvent(player, block = signBlock, hand = EquipmentSlot.OFF_HAND)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() != Event.Result.DENY) { "Off-hand event should not deny block use" }
        assert(!listener.javaMenuOpened) { "Off-hand event should not open the Java menu" }
        assert(!listener.bedrockFormOpened) { "Off-hand event should not open the Bedrock form" }
    }

    @Test
    fun `left-click without a target block does not open a create flow`() {
        val player: Player = mockk(relaxed = true)
        every { player.isSneaking } returns true

        val listener = listenerWithStall()
        val event = interactEvent(player, block = null)
        listener.onSignInteract(event)

        assert(!listener.javaMenuOpened) { "Missing target should not open the Java menu" }
        assert(!listener.bedrockFormOpened) { "Missing target should not open the Bedrock form" }
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
        verify { player.sendMessage(any<Component>()) }
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
        verify { player.sendMessage(any<Component>()) }
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
        verify { player.sendMessage(any<Component>()) }
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
        val listener = listenerWithStall(
            stallRepo = stallRepo,
            shopRepo = shopRepo,
            stall = stall,
            managesStall = false,
        )

        val event = interactEvent(player, block = signBlock)
        listener.onSignInteract(event)

        assert(event.useInteractedBlock() != Event.Result.DENY) { "Event should not be cancelled when player cannot manage stall" }
        verify { player.sendMessage(any<Component>()) }
    }
}
