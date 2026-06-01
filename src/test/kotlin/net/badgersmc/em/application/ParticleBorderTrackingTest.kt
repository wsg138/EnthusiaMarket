package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.RegionProvider.RegionBounds
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ParticleBorderTrackingTest {

    @Test fun `add tracks an outline and purgeExpired removes expired`() {
        val svc = ParticleBorderService()
        val player = UUID.randomUUID()
        val now = Instant.now()
        svc.addOutline(player, "stall1", "world", RegionBounds(0, 0, 0, 4, 4, 4), expiresAt = now.plusSeconds(10))
        assertEquals(1, svc.activeCount())
        svc.purgeExpired(now.plusSeconds(11))
        assertEquals(0, svc.activeCount())
    }

    @Test fun `re-adding same player+stall replaces the entry`() {
        val svc = ParticleBorderService()
        val player = UUID.randomUUID()
        val now = Instant.now()
        svc.addOutline(player, "stall1", "world", RegionBounds(0, 0, 0, 4, 4, 4), now.plusSeconds(10))
        svc.addOutline(player, "stall1", "world", RegionBounds(0, 0, 0, 4, 4, 4), now.plusSeconds(20))
        assertEquals(1, svc.activeCount())
    }
}