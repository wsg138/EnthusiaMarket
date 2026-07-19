package net.badgersmc.em.interaction

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.application.StallBuyoutService
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.StallId
import org.bukkit.entity.Player
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PurchaseFlowTest {
    private val playerId = UUID.randomUUID()
    private val socketAddress = mockk<InetSocketAddress>(relaxed = true) {
        every { address.hostAddress } returns "127.0.0.1"
    }
    private val player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns playerId
        every { address } returns socketAddress
    }
    private val stallId = StallId("stall_01")

    @Test fun `personal and guild selections call the matching buyout methods`() {
        val buyout = mockk<StallBuyoutService>()
        every { buyout.buy(any(), any(), any(), any()) } returns StallBuyoutService.Result.Rejected("test")
        every { buyout.buyForGuild(any(), any(), any(), any()) } returns StallBuyoutService.Result.Rejected("test")

        PurchaseFlow.execute(player, stallId, 500, false, buyout)
        PurchaseFlow.execute(player, stallId, 500, true, buyout)

        verify(exactly = 1) { buyout.buy(stallId, playerId, 500, any()) }
        verify(exactly = 1) { buyout.buyForGuild(stallId, playerId, 500, any()) }
    }

    @Test fun `guild option requires membership and shop permission`() {
        val provider = mockk<GuildProvider>()
        val guild = GuildProvider.GuildRef("guild-1", "Badgers")
        every { provider.guildOf(playerId) } returns guild
        every { provider.hasShopPermission(playerId, guild.id, GuildProvider.GuildPermission.MANAGE_SHOPS) } returns false
        assertNull(PurchaseFlow.eligibleGuild(player, provider))
        every { provider.hasShopPermission(playerId, guild.id, GuildProvider.GuildPermission.MANAGE_SHOPS) } returns true
        assertEquals("Badgers", PurchaseFlow.eligibleGuild(player, provider)?.name)
    }
}
