package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.ports.RegionProvisioner
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportStallsServiceTest {

    private fun provider(rent: RentTerms): DefaultRentTermsProvider =
        mockk { every { current() } returns rent }

    private fun service(
        regions: List<RegionProvider.RegionRef>,
        existing: List<Stall> = emptyList(),
        provisioner: RegionProvisioner =
            mockk(relaxed = true),
    ): Triple<ImportStallsService, StallRepository, RegionProvisioner> {
        val regionProvider = mockk<RegionProvider>()
        every { regionProvider.listByPrefix("world", "stall") } returns regions
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findByRegion(any(), any()) } answers {
            val region = secondArg<String>()
            existing.firstOrNull { it.regionId == region }
        }
        val svc = ImportStallsService(
            regionProvider, repo, rentTermsProvider = provider(RentTerms.formula(1.0)),
            provisioner = provisioner, stallPriority = 20,
        )
        return Triple(svc, repo, provisioner)
    }

    @Test fun `creates a stall for every matched region`() {
        val (svc, repo, _) = service(
            regions = listOf(
                RegionProvider.RegionRef("world", "stall1"),
                RegionProvider.RegionRef("world", "stall2")
            )
        )
        val result = svc.import("world", "stall")
        assertEquals(2, result.created)
        assertEquals(0, result.skipped)
        verify(exactly = 2) { repo.create(any()) }
    }

    @Test fun `skips regions that already correspond to a stall`() {
        val existing = Stall(
            id = StallId("stall1"),
            regionId = "stall1",
            world = "world",
            state = net.badgersmc.em.domain.stall.StallState.UNOWNED,
            owner = net.badgersmc.em.domain.stall.OwnerRef.unowned(),
            ownerSince = null,
            winningBid = 0L,
            rentTerms = RentTerms.formula(1.0)
        )
        val (svc, repo, _) = service(
            regions = listOf(
                RegionProvider.RegionRef("world", "stall1"),
                RegionProvider.RegionRef("world", "stall2")
            ),
            existing = listOf(existing)
        )
        val result = svc.import("world", "stall")
        assertEquals(1, result.created)
        assertEquals(1, result.skipped)
        val created = slot<Stall>()
        verify(exactly = 1) { repo.create(capture(created)) }
        assertEquals("stall2", created.captured.regionId)
    }

    @Test fun `provisions every matched region on import`() {
        val provisioner = mockk<RegionProvisioner>(relaxed = true)
        val (svc, _, prov) = service(
            regions = listOf(
                RegionProvider.RegionRef("world", "stall1"),
                RegionProvider.RegionRef("world", "stall2"),
            ),
            provisioner = provisioner,
        )
        svc.import("world", "stall")
        verify { prov.provision("world", "stall1", 20) }
        verify { prov.provision("world", "stall2", 20) }
    }

    @Test fun `provisions even already-imported stalls so flags get fixed`() {
        val existing = Stall(
            id = StallId("stall1"), regionId = "stall1", world = "world",
            state = net.badgersmc.em.domain.stall.StallState.UNOWNED,
            owner = net.badgersmc.em.domain.stall.OwnerRef.unowned(),
            ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
        )
        val provisioner = mockk<RegionProvisioner>(relaxed = true)
        val (svc, _, prov) = service(
            regions = listOf(RegionProvider.RegionRef("world", "stall1")),
            existing = listOf(existing),
            provisioner = provisioner,
        )
        val result = svc.import("world", "stall")
        assertEquals(0, result.created)
        assertEquals(1, result.skipped)
        verify { prov.provision("world", "stall1", 20) }
    }
}
