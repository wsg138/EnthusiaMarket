package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StallEvictionServiceTest {

    private fun ownedStall(state: StallState = StallState.OWNED) = Stall(
        id = StallId("stall1"), regionId = "stall1", world = "world",
        state = state, owner = OwnerRef.solo(UUID.randomUUID()),
        ownerSince = Instant.now(), winningBid = 1000L, rentTerms = RentTerms.formula(1.0),
        members = setOf(UUID.randomUUID()), nextRentAt = Instant.now(),
    )

    private fun service(
        repo: StallRepository,
        regions: RegionMemberSync,
        shops: net.badgersmc.em.domain.shop.ShopRepository = mockk(relaxed = true),
    ): StallEvictionService {
        val config = mockk<EnthusiaMarketConfig>()
        val schem = mockk<EnthusiaMarketConfig.Schematics>()
        every { schem.enabled } returns false
        every { config.schematics } returns schem
        return StallEvictionService(repo, shops, regions, config, ipLimiter = mockk<IpLimiter>(relaxed = true))
    }

    @Test fun `evict resets an owned stall to UNOWNED and clears WG`() {
        val repo = mockk<StallRepository>(relaxed = true)
        val regions = mockk<RegionMemberSync>(relaxed = true)
        every { repo.findById(StallId("stall1")) } returns ownedStall()
        val saved = slot<Stall>()
        every { repo.save(capture(saved)) } returns Unit

        val result = service(repo, regions).evict(StallId("stall1"))

        assertIs<StallEvictionService.Result.Evicted>(result)
        assertEquals(StallState.UNOWNED, saved.captured.state)
        assertEquals(OwnerType.NONE, saved.captured.owner.type)
        assertEquals(0L, saved.captured.winningBid)
        assertEquals(emptySet(), saved.captured.members)
        verify { regions.clearOwnersAndMembers("world", "stall1") }
    }

    /** M-4 (audit 2026-06-09): the next owner must not inherit the evicted owner's live shops. */
    @Test fun `evict wipes shops bound to the stall`() {
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findById(StallId("stall1")) } returns ownedStall()
        val shops = mockk<net.badgersmc.em.domain.shop.ShopRepository>(relaxed = true)
        val boundShop = net.badgersmc.em.domain.shop.Shop(
            id = 7, stallId = "stall1", owner = UUID.randomUUID(),
            signWorld = "world", signX = 0, signY = 64, signZ = 0,
            containerWorld = "world", containerX = 0, containerY = 63, containerZ = 0,
            sellItem = "item", sellAmount = 1, costItem = "item", costAmount = 1,
        )
        every { shops.findByStall("stall1") } returns listOf(boundShop)

        val result = service(repo, mockk(relaxed = true), shops).evict(StallId("stall1"))

        assertIs<StallEvictionService.Result.Evicted>(result)
        verify { shops.delete(7) }
    }

    @Test fun `evict returns NotFound for a missing stall`() {
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findById(StallId("nope")) } returns null
        val result = service(repo, mockk(relaxed = true)).evict(StallId("nope"))
        assertIs<StallEvictionService.Result.NotFound>(result)
    }

    @Test fun `evict returns NotOwned for an already-unowned stall`() {
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findById(StallId("stall1")) } returns ownedStall(state = StallState.UNOWNED)
        val result = service(repo, mockk(relaxed = true)).evict(StallId("stall1"))
        assertIs<StallEvictionService.Result.NotOwned>(result)
        verify(exactly = 0) { repo.save(any()) }
    }
}