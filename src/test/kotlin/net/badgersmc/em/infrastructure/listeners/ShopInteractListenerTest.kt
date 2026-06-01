package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.interaction.MenuFactory
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.test.Test

class ShopInteractListenerTest {

    private val repo: ShopRepository = mockk(relaxed = true)
    private val menuFactory: MenuFactory = mockk(relaxed = true)
    private val tradeService: net.badgersmc.em.application.ContainerTradeService = mockk(relaxed = true)

    /** Create a mock Location at fixed coordinates. */
    private fun location(x: Int = 100, y: Int = 64, z: Int = 200): Location {
        val loc: Location = mockk(relaxed = true)
        every { loc.world?.name } returns "world"
        every { loc.blockX } returns x
        every { loc.blockY } returns y
        every { loc.blockZ } returns z
        return loc
    }

    /** Create a mock sign block at the given location. */
    private fun signBlock(loc: Location = location()): Block {
        val block: Block = mockk(relaxed = true)
        val sign: Sign = mockk(relaxed = true)
        every { block.state } returns sign
        every { block.type } returns Material.OAK_WALL_SIGN
        every { block.location } returns loc
        return block
    }

    /** Create a mock non-sign block. */
    private fun nonSignBlock(): Block {
        val block: Block = mockk(relaxed = true)
        every { block.state } returns mockk<org.bukkit.block.Chest>(relaxed = true)
        return block
    }

    /** Create a sample Shop for testing. */
    private fun sampleShop(): Shop = Shop(
        stallId = "stall_01",
        owner = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        signWorld = "world", signX = 100, signY = 64, signZ = 200,
        containerWorld = "world", containerX = 101, containerY = 64, containerZ = 201,
        sellItem = "base64item", sellAmount = 1,
        costItem = "base64cost", costAmount = 10
    )

    /** Helper: create a PlayerInteractEvent. */
    private fun interactEvent(
        player: Player,
        action: Action = Action.RIGHT_CLICK_BLOCK,
        block: Block?,
        hand: EquipmentSlot = EquipmentSlot.HAND
    ): PlayerInteractEvent {
        val loc = block?.location ?: location()
        if (block != null) every { block.location } returns loc
        return PlayerInteractEvent(player, action, null as ItemStack?, block, BlockFace.NORTH, hand)
    }

    @Test
    fun `right-click on registered shop sign opens PurchaseMenu`() {
        val player: Player = mockk(relaxed = true)
        every { menuFactory.shouldUseBedrockMenus(player) } returns false

        val loc = location()
        val block = signBlock(loc)
        val shop = sampleShop()
        every { repo.findBySign("world", 100, 64, 200) } returns shop

        val listener = object : ShopInteractListener(repo, menuFactory, tradeService, mockk(relaxed = true), mockk(relaxed = true)) {
            var purchaseMenuOpened = false
            override fun openPurchaseMenu(player: Player, shop: Shop) {
                purchaseMenuOpened = true
            }
        }

        val event = interactEvent(player, block = block)
        listener.onSignRightClick(event)

        assert(event.isCancelled) { "Event should be cancelled for registered shop sign" }
        assert(listener.purchaseMenuOpened) { "PurchaseMenu should have been opened" }
    }

    @Test
    fun `right-click on non-shop sign does nothing`() {
        val player: Player = mockk(relaxed = true)
        val loc = location()
        val block = signBlock(loc)
        every { repo.findBySign("world", 100, 64, 200) } returns null

        val listener = ShopInteractListener(repo, menuFactory, tradeService, mockk(relaxed = true), mockk(relaxed = true))
        val event = interactEvent(player, block = block)
        listener.onSignRightClick(event)

        assert(!event.isCancelled) { "Event should not be cancelled for non-shop sign" }
    }

    @Test
    fun `left-click does not trigger PurchaseMenu`() {
        val player: Player = mockk(relaxed = true)
        val block = signBlock()
        val listener = ShopInteractListener(repo, menuFactory, tradeService, mockk(relaxed = true), mockk(relaxed = true))
        val event = interactEvent(player, action = Action.LEFT_CLICK_BLOCK, block = block)
        listener.onSignRightClick(event)
        assert(!event.isCancelled) { "Left-click should not cancel event" }
    }

    @Test
    fun `right-click on non-sign block does nothing`() {
        val player: Player = mockk(relaxed = true)
        val block = nonSignBlock()
        val listener = ShopInteractListener(repo, menuFactory, tradeService, mockk(relaxed = true), mockk(relaxed = true))
        val event = interactEvent(player, block = block)
        listener.onSignRightClick(event)
        assert(!event.isCancelled) { "Should not cancel for non-sign blocks" }
    }

    @Test
    fun `off-hand right-click does not trigger PurchaseMenu`() {
        val player: Player = mockk(relaxed = true)
        val block = signBlock()
        val listener = ShopInteractListener(repo, menuFactory, tradeService, mockk(relaxed = true), mockk(relaxed = true))
        val event = interactEvent(player, block = block, hand = EquipmentSlot.OFF_HAND)
        listener.onSignRightClick(event)
        assert(!event.isCancelled) { "Off-hand should not cancel event" }
    }
}