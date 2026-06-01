package net.badgersmc.em.infrastructure.commands

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.domain.stall.*
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminCommandsEntityLimitTest {

    private fun stall(kind: String = "default") = Stall(
        id = StallId("stall1"), regionId = "stall1", world = "world",
        state = StallState.UNOWNED, owner = OwnerRef.unowned(),
        ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
        kind = kind,
    )

    @Test fun `setkind persists the new kind`() {
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findById(StallId("stall1")) } returns stall()
        val saved = slot<Stall>()
        every { repo.save(capture(saved)) } returns Unit

        val updated = AdminCommands.applySetKind(repo, "stall1", "shop")
        assertEquals(true, updated)
        assertEquals("shop", saved.captured.kind)
    }

    @Test fun `setkind returns false for missing stall`() {
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findById(StallId("nope")) } returns null
        assertEquals(false, AdminCommands.applySetKind(repo, "nope", "shop"))
    }

    @Test fun `entitylimit set persists per-stall override`() {
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findById(StallId("stall1")) } returns stall()
        val saved = slot<Stall>()
        every { repo.save(capture(saved)) } returns Unit

        val ok = AdminCommands.applyEntityLimit(repo, "stall1", "villager", 3)
        assertEquals(true, ok)
        assertEquals(3, saved.captured.extraEntities["villager"])
    }
}
