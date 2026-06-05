package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class StallOwnershipCounterTest {

    private val player = UUID.randomUUID()

    private fun stall(owner: OwnerRef, kind: String) = mockk<Stall> {
        every { this@mockk.owner } returns owner
        every { this@mockk.kind } returns kind
    }

    @Test fun `counts SOLO-owned by the player, grouped by kind, excluding guild and others`() {
        val repo = mockk<StallRepository> {
            every { all() } returns listOf(
                stall(OwnerRef.solo(player), "default"),
                stall(OwnerRef.solo(player), "default"),
                stall(OwnerRef.solo(player), "farm"),
                stall(OwnerRef.guild("g1"), "default"),          // guild — excluded
                stall(OwnerRef.solo(UUID.randomUUID()), "default"), // someone else — excluded
            )
        }
        val c = StallOwnershipCounter(repo).counts(player)
        assertEquals(3, c.total)
        assertEquals(2, c.byKind["default"])
        assertEquals(1, c.byKind["farm"])
    }

    @Test fun `zero for a player who owns none`() {
        val repo = mockk<StallRepository> { every { all() } returns emptyList() }
        val c = StallOwnershipCounter(repo).counts(player)
        assertEquals(0, c.total)
        assertEquals(emptyMap(), c.byKind)
    }
}
