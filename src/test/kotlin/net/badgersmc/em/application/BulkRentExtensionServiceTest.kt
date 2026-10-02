package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.MarketMutationGate
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BulkRentExtensionServiceTest {

    private val now = Instant.parse("2026-09-29T18:00:00Z")
    private val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private val config = EnthusiaMarketConfig()

    private fun stall(
        id: String,
        state: StallState,
        nextRentAt: Instant?,
        ownerSince: Instant = now.minus(Duration.ofDays(30)),
    ) = Stall(
        id = StallId(id),
        regionId = id,
        world = "world",
        state = state,
        owner = if (state == StallState.UNOWNED) OwnerRef.unowned() else OwnerRef.solo(owner),
        ownerSince = ownerSince,
        winningBid = if (state == StallState.UNOWNED) 0L else 1000L,
        rentTerms = RentTerms.flat(50L),
        nextRentAt = nextRentAt,
    )

    @Test
    fun `bulk credit extends only unlocked active stalls and isolates failures`() {
        val ownedFuture = stall("owned-future", StallState.OWNED, now.plus(Duration.ofDays(2)))
        val ownedNull = stall(
            "owned-null",
            StallState.OWNED,
            nextRentAt = null,
            ownerSince = now.minus(Duration.ofDays(2)),
        )
        val graceRecover = stall("grace-recover", StallState.GRACE, now.minus(Duration.ofDays(1)))
        val graceStillLate = stall("grace-late", StallState.GRACE, now.minus(Duration.ofDays(10)))
        val failed = stall("failed", StallState.OWNED, now.plus(Duration.ofDays(1)))
        val locked = stall("locked", StallState.OWNED, now.plus(Duration.ofDays(1)))
        val unowned = stall("unowned", StallState.UNOWNED, null)
        val auctioning = stall("auctioning", StallState.AUCTIONING, null)
        val hold = stall("hold", StallState.MODERATION_HOLD, null)

        val repo = mockk<StallRepository>()
        every { repo.all() } returns listOf(
            ownedFuture, ownedNull, graceRecover, graceStillLate, failed,
            locked, unowned, auctioning, hold,
        )
        val saved = mutableListOf<Stall>()
        every { repo.save(any()) } answers {
            val candidate = firstArg<Stall>()
            if (candidate.id.value == "failed") error("synthetic save failure")
            saved += candidate
        }
        val gate = object : MarketMutationGate {
            override fun isStallLocked(stallId: String): Boolean = stallId == "locked"
        }

        val service = BulkRentExtensionService(repo, config, gate)

        val result = service.extendAll(Duration.ofDays(7), now)

        assertEquals(4, result.updated)
        assertEquals(1, result.recovered)
        assertEquals(4, result.skipped)
        assertEquals(1, result.failed)

        val byId = saved.associateBy { it.id.value }
        assertEquals(now.plus(Duration.ofDays(9)), byId.getValue("owned-future").nextRentAt)
        assertEquals(now.plus(Duration.ofDays(6)), byId.getValue("owned-null").nextRentAt)
        assertEquals(now.plus(Duration.ofDays(6)), byId.getValue("grace-recover").nextRentAt)
        assertEquals(StallState.OWNED, byId.getValue("grace-recover").state)
        assertEquals(now.minus(Duration.ofDays(3)), byId.getValue("grace-late").nextRentAt)
        assertEquals(StallState.GRACE, byId.getValue("grace-late").state)
        assertEquals(setOf("owned-future", "owned-null", "grace-recover", "grace-late"), byId.keys)
    }

    @Test
    fun `mutation gate failure is isolated per stall and prevents save`() {
        val candidate = stall("gate-failed", StallState.OWNED, now.plus(Duration.ofDays(1)))
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.all() } returns listOf(candidate)
        val gate = object : MarketMutationGate {
            override fun isStallLocked(stallId: String): Boolean {
                error("synthetic gate failure")
            }
        }

        val result = BulkRentExtensionService(repo, config, gate)
            .extendAll(Duration.ofDays(7), now)

        assertEquals(0, result.updated)
        assertEquals(0, result.skipped)
        assertEquals(1, result.failed)
        verify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `bulk credit rejects zero and negative durations before loading stalls`() {
        val repo = mockk<StallRepository>()
        val service = BulkRentExtensionService(repo, config, MarketMutationGate.Open)

        for (duration in listOf(Duration.ZERO, Duration.ofMinutes(-1))) {
            assertFailsWith<IllegalArgumentException> {
                service.extendAll(duration, now)
            }
        }
    }
}
