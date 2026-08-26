package net.badgersmc.em.application

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import net.badgersmc.em.domain.maintenance.FreezeShiftResult
import net.badgersmc.em.domain.maintenance.MaintenanceFreezeRepository
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaintenanceFreezeServiceTest {

    private val repo = mockk<MaintenanceFreezeRepository>()

    private fun service() = MaintenanceFreezeService(repo)

    // --- isFrozen / lazy load ---

    @Test fun `isFrozen is false when repo reports no freeze`() {
        every { repo.frozenSince() } returns null
        assertFalse(service().isFrozen())
    }

    @Test fun `isFrozen is true when repo reports an active freeze`() {
        every { repo.frozenSince() } returns Instant.parse("2026-06-01T10:00:00Z")
        assertTrue(service().isFrozen())
    }

    @Test fun `freeze state is loaded from repo exactly once and cached`() {
        every { repo.frozenSince() } returns null
        val svc = service()
        svc.isFrozen()
        svc.isFrozen()
        svc.frozenSince()
        verify(exactly = 1) { repo.frozenSince() }
    }

    // --- begin ---

    @Test fun `begin activates and updates the cache`() {
        every { repo.frozenSince() } returns null
        every { repo.begin(any()) } just runs
        val svc = service()
        val now = Instant.parse("2026-06-01T10:00:00Z")

        val result = svc.begin(now)

        assertTrue(result is MaintenanceFreezeResult.Activated)
        assertEquals(now, (result as MaintenanceFreezeResult.Activated).since)
        verify { repo.begin(now) }
        assertTrue(svc.isFrozen())
        assertEquals(now, svc.frozenSince())
    }

    @Test fun `begin when already frozen returns AlreadyActive and does not write`() {
        val since = Instant.parse("2026-06-01T10:00:00Z")
        every { repo.frozenSince() } returns since
        val svc = service()

        val result = svc.begin(Instant.parse("2026-06-02T00:00:00Z"))

        assertTrue(result is MaintenanceFreezeResult.AlreadyActive)
        assertEquals(since, (result as MaintenanceFreezeResult.AlreadyActive).since)
        verify(exactly = 0) { repo.begin(any()) }
    }

    // --- end ---

    @Test fun `end when frozen shifts timers by the elapsed duration and clears the cache`() {
        val since = Instant.parse("2026-06-01T10:00:00Z")
        every { repo.frozenSince() } returns since
        every { repo.unfreeze(any()) } returns FreezeShiftResult(stalls = 3, auctions = 2)
        val svc = service()
        assertEquals(since, svc.frozenSince())  // prime the cache

        val result = svc.end(Instant.parse("2026-06-02T10:00:00Z"))

        assertTrue(result is MaintenanceFreezeResult.Lifted)
        result as MaintenanceFreezeResult.Lifted
        assertEquals(3, result.stalls)
        assertEquals(2, result.auctions)
        assertEquals(Duration.ofHours(24), result.elapsed)
        verify { repo.unfreeze(Duration.ofHours(24).toMillis()) }
        assertFalse(svc.isFrozen())
        assertNull(svc.frozenSince())
    }

    @Test fun `end when not frozen returns NotFrozen`() {
        every { repo.frozenSince() } returns null
        val svc = service()

        val result = svc.end(Instant.parse("2026-06-02T10:00:00Z"))

        assertTrue(result is MaintenanceFreezeResult.NotFrozen)
        verify(exactly = 0) { repo.unfreeze(any()) }
    }

    @Test fun `end never shifts backwards when the clock is behind the freeze start`() {
        val since = Instant.parse("2026-06-02T10:00:00Z")
        every { repo.frozenSince() } returns since
        every { repo.unfreeze(any()) } returns FreezeShiftResult(0, 0)
        val svc = service()

        val result = svc.end(Instant.parse("2026-06-01T10:00:00Z"))  // clock skew

        assertTrue(result is MaintenanceFreezeResult.Lifted)
        verify { repo.unfreeze(0L) }
    }

    // --- status ---

    @Test fun `status reflects an active freeze`() {
        val since = Instant.parse("2026-06-01T10:00:00Z")
        every { repo.frozenSince() } returns since
        val status = service().status()
        assertTrue(status.frozen)
        assertEquals(since, status.since)
    }

    @Test fun `status reflects no freeze`() {
        every { repo.frozenSince() } returns null
        val status = service().status()
        assertFalse(status.frozen)
        assertNull(status.since)
    }
}
