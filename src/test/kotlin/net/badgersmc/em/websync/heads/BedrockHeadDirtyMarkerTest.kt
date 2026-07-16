@file:Suppress("FunctionNaming")

package net.badgersmc.em.websync.heads

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.websync.WebsiteSyncDirtySink
import java.util.UUID
import kotlin.test.Test

class BedrockHeadDirtyMarkerTest {
    @Test
    fun `published capture dirties matching solo and unbannered guild leader stalls only`() {
        val player = UUID.randomUUID()
        val other = UUID.randomUUID()
        val stalls = mockk<StallRepository>()
        val guilds = mockk<GuildProvider>()
        val dirty = mockk<WebsiteSyncDirtySink>(relaxed = true)
        every { stalls.all() } returns listOf(
            stall("stall1", OwnerRef.solo(player)),
            stall("stall2", OwnerRef.solo(other)),
            stall("stall3", OwnerRef.guild("guild-no-banner")),
            stall("stall4", OwnerRef.guild("guild-banner")),
        )
        every { guilds.visualById("guild-no-banner") } returns GuildProvider.GuildVisual(player, null)
        every { guilds.visualById("guild-banner") } returns GuildProvider.GuildVisual(
            player,
            GuildProvider.BannerDesign("RED", emptyList()),
        )

        BedrockHeadDirtyMarker(stalls, guilds, dirty).mark(player)

        verify(exactly = 1) { dirty.markDirty("stall1") }
        verify(exactly = 1) { dirty.markDirty("stall3") }
        verify(exactly = 0) { dirty.markDirty("stall2") }
        verify(exactly = 0) { dirty.markDirty("stall4") }
    }

    private fun stall(id: String, owner: OwnerRef): Stall = mockk {
        every { this@mockk.id } returns StallId(id)
        every { this@mockk.owner } returns owner
    }
}
