package net.badgersmc.em

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.StallId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Smoke test: verify the main plugin class and config structure are valid.
 * Full MockBukkit lifecycle tests are replaced by targeted unit tests
 * in domain, application, and infrastructure packages.
 *
 * Reason: Paper 1.21+ lifecycle API (LifecycleEvents.COMMANDS) is not
 * supported by MockBukkit; Nexus's Paper command registration requires it.
 */
class EnthusiaMarketPluginTest {

    @Test fun `config can be created with defaults`() {
        val cfg = EnthusiaMarketConfig()
        assertEquals("world", cfg.market.world)
        assertEquals("stall_", cfg.market.regionPrefix)
        assertEquals("formula", cfg.rent.mode)
    }

    @Test fun `RentTerms can be created from config`() {
        val cfg = EnthusiaMarketConfig()
        val rent = RentTerms.formula(cfg.rent.formulaPct)
        assertEquals(0.01, rent.pct)
    }

    @Test fun `StallId ensures non-blank value`() {
        val id = StallId("stall_01")
        assertNotNull(id)
        assertEquals("stall_01", id.value)
    }
}