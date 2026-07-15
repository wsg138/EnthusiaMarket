package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallState
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RentTimingPolicyTest {
    private val config = EnthusiaMarketConfig().apply {
        rent.collectionInterval = "P2D"
        rent.gracePeriod = "P3D"
    }
    private val ownerSince = Instant.parse("2026-07-01T12:00:00Z")

    @Test
    fun `owned legacy stall derives next collection from owner timestamp`() {
        val stall = stall(StallState.OWNED, nextRentAt = null)

        assertEquals(ownerSince.plusSeconds(2 * 86_400L), RentTimingPolicy.effectiveNextRentAt(stall, config))
        assertNull(RentTimingPolicy.graceEndsAt(stall, config))
    }

    @Test
    fun `grace stall exposes configured deadline rather than a rent estimate`() {
        val stall = stall(StallState.GRACE, nextRentAt = null)

        assertNull(RentTimingPolicy.effectiveNextRentAt(stall, config))
        // Grace window starts at ownerSince + collectionInterval (2d), then + gracePeriod (3d) = 5d
        assertEquals(ownerSince.plusSeconds(5 * 86_400L), RentTimingPolicy.graceEndsAt(stall, config))
    }

    @Test
    fun `emergency auction has no grace deadline`() {
        val stall = stall(StallState.EMERGENCY_AUCTIONING, nextRentAt = null)

        assertNull(RentTimingPolicy.graceEndsAt(stall, config))
    }

    private fun stall(state: StallState, nextRentAt: Instant?) = Stall(
        id = StallId("stall1"),
        regionId = "stall1",
        world = "world",
        state = state,
        owner = OwnerRef.solo(UUID.fromString("00000000-0000-4000-8000-000000000001")),
        ownerSince = ownerSince,
        winningBid = 100L,
        rentTerms = RentTerms.flat(1L),
        nextRentAt = nextRentAt,
    )
}
