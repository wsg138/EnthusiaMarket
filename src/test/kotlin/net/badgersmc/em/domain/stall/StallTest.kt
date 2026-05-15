package net.badgersmc.em.domain.stall

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StallTest {
    private val baseStall = Stall(
        id = StallId("stall_01"),
        regionId = "stall_01",
        world = "world",
        state = StallState.UNOWNED,
        owner = OwnerRef.unowned(),
        ownerSince = null,
        winningBid = 0L,
        rentTerms = RentTerms.formula(1.0)
    )

    @Test fun `a fresh stall is UNOWNED with no owner`() {
        assertEquals(StallState.UNOWNED, baseStall.state)
        assertEquals(OwnerType.NONE, baseStall.owner.type)
        assertEquals(0L, baseStall.winningBid)
    }

    @Test fun `awarding a stall transitions to OWNED with bid and timestamp`() {
        val now = Instant.parse("2026-05-15T10:00:00Z")
        val owner = OwnerRef.solo(UUID.randomUUID())
        val awarded = baseStall.awardTo(owner, winningBid = 1000L, at = now)
        assertEquals(StallState.OWNED, awarded.state)
        assertEquals(owner, awarded.owner)
        assertEquals(1000L, awarded.winningBid)
        assertEquals(now, awarded.ownerSince)
    }

    @Test fun `awarding requires a non-unowned owner`() {
        assertFailsWith<IllegalArgumentException> {
            baseStall.awardTo(OwnerRef.unowned(), winningBid = 1L, at = Instant.now())
        }
    }

    @Test fun `awarding requires a positive winning bid`() {
        assertFailsWith<IllegalArgumentException> {
            baseStall.awardTo(OwnerRef.solo(UUID.randomUUID()), winningBid = 0L, at = Instant.now())
        }
    }
}
