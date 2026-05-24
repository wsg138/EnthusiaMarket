package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import net.badgersmc.em.domain.shop.ShopSign
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.shop.SignRepository
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.SignChangeEvent
import java.util.UUID
import kotlin.test.Test

class SignPlaceListenerTest {

    private val worldName = "world"
    private val world = mockk<World> {
        every { name } returns worldName
    }
    private val location = Location(world, 100.0, 64.0, 200.0)
    private val block = mockk<Block>(relaxed = true) {
        every { type } returns Material.OAK_SIGN
    }
    private val player = mockk<Player> {
        every { uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000001")
        every { name } returns "TestPlayer"
    }

    private val stallId = StallId("stall_01")
    private val sampleStall = Stall(
        id = stallId,
        regionId = "stall_01",
        world = worldName,
        state = StallState.OWNED,
        owner = OwnerRef.solo(UUID.fromString("00000000-0000-0000-0000-000000000002")),
        ownerSince = null,
        winningBid = 1000L,
        rentTerms = RentTerms.formula(1.0)
    )

    private val signRepo = mockk<SignRepository>(relaxed = true)
    private val stallRepo = mockk<StallRepository>()

    /** Create a SignChangeEvent with the given lines as Component text. */
    private fun signEvent(vararg lines: String): SignChangeEvent {
        val components: MutableList<net.kyori.adventure.text.Component> =
            lines.map { net.kyori.adventure.text.Component.text(it) }.toMutableList()
        // Pad to 4 lines if needed
        while (components.size < 4) components.add(net.kyori.adventure.text.Component.empty())
        return SignChangeEvent(block, player, components)
    }

    /** Create a listener that returns [stall] when findStallAt is called. */
    private fun listenerInStall(stall: Stall? = sampleStall): SignPlaceListener {
        val listener = SignPlaceListener(stallRepo, signRepo)
        return spyk(listener).apply {
            every { findStallAt(any()) } returns stall
        }
    }

    @Test
    fun `sign with BUY text inside stall is registered via SignRepository`() {
        val event = signEvent("[BUY]", "DIAMOND", "100", "10")
        val listener = listenerInStall()

        listener.onSignPlace(event)

        verify { signRepo.create(match { sign ->
            sign.stallId == stallId &&
                sign.direction == SignDirection.BUY &&
                sign.price == 100L &&
                sign.signLocation.isNotEmpty()
        }) }
    }

    @Test
    fun `sign with SELL text inside stall is registered`() {
        val event = signEvent("[SELL]", "DIAMOND", "100", "10")
        val listener = listenerInStall()

        listener.onSignPlace(event)

        verify { signRepo.create(match { sign ->
            sign.stallId == stallId &&
                sign.direction == SignDirection.SELL &&
                sign.price == 100L
        }) }
    }

    @Test
    fun `sign with no BUY or SELL text is NOT registered`() {
        val event = signEvent("Hello", "World", "", "")
        val listener = listenerInStall()

        listener.onSignPlace(event)

        verify(inverse = true) { signRepo.create(any()) }
    }

    @Test
    fun `sign placed outside any stall is NOT registered`() {
        val event = signEvent("[BUY]", "DIAMOND", "100", "10")
        val listener = listenerInStall(stall = null)

        listener.onSignPlace(event)

        verify(inverse = true) { signRepo.create(any()) }
    }
}