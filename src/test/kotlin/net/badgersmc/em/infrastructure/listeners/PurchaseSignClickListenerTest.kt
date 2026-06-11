package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.application.StallBuyoutService
import net.badgersmc.em.application.StallRentExtensionService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.sign.PurchaseSign
import net.badgersmc.em.domain.sign.PurchaseSignRepository
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import kotlin.test.Test
import net.kyori.adventure.text.Component

class PurchaseSignClickListenerTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun signBlock(world: String = "world", x: Int = 10, y: Int = 64, z: Int = 20): Block {
        val block = mockk<Block>(relaxed = true)
        every { block.type } returns Material.OAK_SIGN
        val w = block.world
        every { w.name } returns world
        every { block.x } returns x
        every { block.y } returns y
        every { block.z } returns z
        return block
    }

    private fun interactEvent(player: Player, block: Block) =
        PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null as ItemStack?, block, org.bukkit.block.BlockFace.NORTH, EquipmentSlot.HAND)

    private fun unownedStall(): Stall {
        val stall = mockk<Stall>(relaxed = true)
        every { stall.state } returns StallState.UNOWNED
        return stall
    }

    private fun purchaseSign(stallId: String = "stall_01", price: Long = 500): PurchaseSign {
        val sign = mockk<PurchaseSign>(relaxed = true)
        every { sign.stallId } returns StallId(stallId)
        every { sign.price } returns price
        return sign
    }

    private fun listenerWith(
        signs: PurchaseSignRepository,
        stalls: StallRepository,
        buyout: StallBuyoutService,
    ) = PurchaseSignClickListener(
        signs,
        stalls,
        buyout,
        mockk<StallRentExtensionService>(relaxed = true),
        mockk<EnthusiaMarketConfig>(relaxed = true),
        mockk(relaxed = true),
    )

    @Test
    fun `right-clicking an unowned stall sign without buyout permission is blocked and does not purchase`() {
        val signs = mockk<PurchaseSignRepository>(relaxed = true)
        val stalls = mockk<StallRepository>(relaxed = true)
        val buyout = mockk<StallBuyoutService>(relaxed = true)
        val block = signBlock(x = 10, y = 64, z = 20)
        every { signs.findAt("world", 10, 64, 20) } returns purchaseSign("stall_01", 500)
        every { stalls.findById(StallId("stall_01")) } returns unownedStall()

        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.isSneaking } returns false
        every { player.hasPermission("enthusiamarket.stall.buyout") } returns false

        val listener = listenerWith(signs, stalls, buyout)
        listener.onClick(interactEvent(player, block))

        verify(exactly = 0) { buyout.buy(any(), any(), any()) }
        verify(exactly = 0) { buyout.buyForGuild(any(), any(), any()) }
        verify { player.sendMessage(any<Component>()) }
    }

    @Test
    fun `right-clicking an unowned stall sign with buyout permission performs the purchase`() {
        val signs = mockk<PurchaseSignRepository>(relaxed = true)
        val stalls = mockk<StallRepository>(relaxed = true)
        val buyout = mockk<StallBuyoutService>(relaxed = true)
        val block = signBlock(x = 10, y = 64, z = 20)
        every { signs.findAt("world", 10, 64, 20) } returns purchaseSign("stall_01", 500)
        every { stalls.findById(StallId("stall_01")) } returns unownedStall()

        val actor = UUID.randomUUID()
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns actor
        every { player.isSneaking } returns false
        every { player.hasPermission("enthusiamarket.stall.buyout") } returns true
        every { buyout.buy(StallId("stall_01"), actor, 500) } returns StallBuyoutService.Result.NotFound

        val listener = listenerWith(signs, stalls, buyout)
        listener.onClick(interactEvent(player, block))

        verify { buyout.buy(StallId("stall_01"), actor, 500) }
    }
}
