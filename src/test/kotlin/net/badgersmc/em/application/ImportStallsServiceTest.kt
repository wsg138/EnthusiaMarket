package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportStallsServiceTest {

    private fun service(
        regions: List<RegionProvider.RegionRef>,
        existing: List<Stall> = emptyList()
    ): Pair<ImportStallsService, StallRepository> {
        val regionProvider = mockk<RegionProvider>()
        every { regionProvider.listByPrefix("world", "stall_") } returns regions
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findByRegion(any(), any()) } answers {
            val region = secondArg<String>()
            existing.firstOrNull { it.regionId == region }
        }
        return ImportStallsService(regionProvider, repo, defaultRent = RentTerms.formula(1.0)) to repo
    }

    @Test fun `creates a stall for every matched region`() {
        val (svc, repo) = service(
            regions = listOf(
                RegionProvider.RegionRef("world", "stall_01"),
                RegionProvider.RegionRef("world", "stall_02")
            )
        )
        val result = svc.import("world", "stall_")
        assertEquals(2, result.created)
        assertEquals(0, result.skipped)
        verify(exactly = 2) { repo.create(any()) }
    }

    @Test fun `skips regions that already correspond to a stall`() {
        val existing = Stall(
            id = StallId("stall_01"),
            regionId = "stall_01",
            world = "world",
            state = net.badgersmc.em.domain.stall.StallState.UNOWNED,
            owner = net.badgersmc.em.domain.stall.OwnerRef.unowned(),
            ownerSince = null,
            winningBid = 0L,
            rentTerms = RentTerms.formula(1.0)
        )
        val (svc, repo) = service(
            regions = listOf(
                RegionProvider.RegionRef("world", "stall_01"),
                RegionProvider.RegionRef("world", "stall_02")
            ),
            existing = listOf(existing)
        )
        val result = svc.import("world", "stall_")
        assertEquals(1, result.created)
        assertEquals(1, result.skipped)
        val created = slot<Stall>()
        verify(exactly = 1) { repo.create(capture(created)) }
        assertEquals("stall_02", created.captured.regionId)
    }
}
