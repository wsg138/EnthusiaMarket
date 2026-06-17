package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ST-7 (REQ-003): `/em rent resync` pushes the current config default rent terms onto every existing
 * stall, since terms are snapshotted per-stall at import. Red until [RentTermsResyncService.resync].
 */
class RentTermsResyncServiceTest {

    private fun stall(id: String, terms: RentTerms) = Stall(
        id = StallId(id), regionId = id, world = "world",
        state = StallState.OWNED, owner = OwnerRef.unowned(), ownerSince = null,
        winningBid = 0L, rentTerms = terms,
    )

    @Test
    fun `resync rewrites stalls whose terms differ and skips matching ones`() {
        val target = RentTerms.flat(100)
        val repo = mockk<StallRepository>(relaxUnitFun = true)
        every { repo.all() } returns listOf(
            stall("a", RentTerms.formula(1.0)),   // differs → rewrite
            stall("b", RentTerms.flat(100)),       // already target → skip
            stall("c", RentTerms.formula(5.0)),   // differs → rewrite
        )
        val saved = mutableListOf<Stall>()
        every { repo.save(capture(saved)) } returns Unit

        val changed = RentTermsResyncService(repo, target).resync()

        assertEquals(2, changed)
        assertEquals(setOf("a", "c"), saved.map { it.id.value }.toSet())
        assertEquals(listOf(target, target), saved.map { it.rentTerms })
        verify(exactly = 2) { repo.save(any()) }
    }
}
