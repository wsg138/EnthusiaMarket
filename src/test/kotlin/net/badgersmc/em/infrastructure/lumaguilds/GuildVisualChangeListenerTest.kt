@file:Suppress("FunctionNaming")

package net.badgersmc.em.infrastructure.lumaguilds

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.websync.WebsiteSyncService
import java.util.UUID
import kotlin.test.Test

class GuildVisualChangeListenerTest {
    private val guildId = UUID.randomUUID()
    private val playerId = UUID.randomUUID()
    private val stalls = mockk<StallRepository>()
    private val websiteSync = mockk<WebsiteSyncService>(relaxed = true)
    private val listener = GuildVisualChangeListener(stalls, websiteSync)

    @Test
    fun `banner or leadership change dirties only stalls owned by that guild`() {
        every { stalls.all() } returns listOf(
            stall("stall1", OwnerRef.guild(guildId.toString())),
            stall("stall2", OwnerRef.guild(UUID.randomUUID().toString())),
            stall("stall3", OwnerRef.solo(playerId)),
        )

        listener.markGuildStalls(guildId)

        verify(exactly = 1) { websiteSync.markDirty("stall1") }
        verify(exactly = 0) { websiteSync.markDirty("stall2") }
        verify(exactly = 0) { websiteSync.markDirty("stall3") }
    }

    @Test
    fun `player join refreshes only stalls owned by that player`() {
        every { stalls.all() } returns listOf(
            stall("stall1", OwnerRef.solo(playerId)),
            stall("stall2", OwnerRef.solo(UUID.randomUUID())),
        )

        listener.markPlayerStalls(playerId)

        verify(exactly = 1) { websiteSync.markDirty("stall1") }
        verify(exactly = 0) { websiteSync.markDirty("stall2") }
    }

    private fun stall(id: String, owner: OwnerRef) = Stall(
        StallId(id), id, "world", StallState.OWNED, owner, null, 1, RentTerms.flat(1),
    )
}
