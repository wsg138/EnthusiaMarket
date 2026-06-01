package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StallInfoServiceTest {

    @Test fun `builds info card with all fields for an owned stall`() {
        val stalls = mockk<StallRepository>()
        val regions = mockk<RegionProvider>()
        val owners = mockk<OwnerNameResolver>(relaxed = true)
        every { owners.displayNameFor(any()) } returns "Steve"

        val ownerUuid = java.util.UUID.randomUUID()
        val stall = Stall(
            id = StallId("stall1"), regionId = "stall1", world = "world",
            state = StallState.OWNED, owner = OwnerRef(OwnerType.SOLO, ownerUuid.toString()),
            ownerSince = Instant.now(), winningBid = 1000L, rentTerms = RentTerms.formula(1.0),
            members = setOf(java.util.UUID.randomUUID()),
            nextRentAt = Instant.now().plusSeconds(3600), kind = "shop",
        )
        every { stalls.findById(StallId("stall1")) } returns stall
        every { regions.bounds("world", "stall1") } returns
            RegionProvider.RegionBounds(-52, 120, -279, -47, 126, -274)

        val service = StallInfoService(stalls, regions, owners)
        val info = service.infoFor(StallId("stall1"))!!

        assertEquals("stall1", info.stallId)
        assertEquals("shop", info.kind)
        assertEquals("Steve", info.ownerName)
        assertEquals(1, info.memberCount)
        assertEquals(StallState.OWNED, info.state)
        assertEquals(false, info.available)
        assertNotNull(info.nextRentAt)
        assertEquals("6x7x6", "${info.width}x${info.height}x${info.length}")
        assertTrue(info.currentRent >= 0)
    }

    @Test fun `unowned stall is available`() {
        val stalls = mockk<StallRepository>()
        val regions = mockk<RegionProvider>(relaxed = true)
        val owners = mockk<OwnerNameResolver>(relaxed = true)
        val stall = Stall(
            id = StallId("stall2"), regionId = "stall2", world = "world",
            state = StallState.UNOWNED, owner = OwnerRef.unowned(),
            ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
        )
        every { stalls.findById(StallId("stall2")) } returns stall
        every { regions.bounds(any(), any()) } returns
            RegionProvider.RegionBounds(0, 0, 0, 4, 4, 4)
        val info = StallInfoService(stalls, regions, owners).infoFor(StallId("stall2"))!!
        assertEquals(true, info.available)
    }

    @Test fun `returns null for missing stall`() {
        val stalls = mockk<StallRepository>()
        every { stalls.findById(StallId("nope")) } returns null
        val service = StallInfoService(stalls, mockk(relaxed = true), mockk(relaxed = true))
        assertEquals(null, service.infoFor(StallId("nope")))
    }
}