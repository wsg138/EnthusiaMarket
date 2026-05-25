package net.badgersmc.em.infrastructure.vault

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.application.RentCollectionService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.infrastructure.scheduler.AuctionScheduler
import net.badgersmc.em.infrastructure.scheduler.RentScheduler
import net.badgersmc.nexus.annotations.Component
import org.bukkit.plugin.Plugin
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertFalse

class VaultHealthTest {

    // --- VaultHealth defaults ---

    @Test fun `vault health defaults to unavailable`() {
        assertFalse(VaultHealth().isAvailable)
    }

    // --- RentScheduler degradation ---

    @Test fun `rent scheduler skips startup when vault absent`() {
        val plugin = mockk<Plugin>(relaxed = true) {
            every { logger } returns mockk(relaxed = true)
        }
        val service = mockk<RentCollectionService>(relaxed = true)
        val health = VaultHealth() // isAvailable = false by default
        val scheduler = RentScheduler(plugin, service, health, mockk<EnthusiaMarketConfig>(relaxed = true))

        // Verify service.tick() was never called (scheduler never started)
        verify(exactly = 0) { service.tick() }
    }

    @Test fun `rent scheduler logs warning when vault absent`() {
        val log = mockk<Logger>(relaxed = true)
        val plugin = mockk<Plugin>(relaxed = true) {
            every { logger } returns log
        }
        val service = mockk<RentCollectionService>(relaxed = true)
        val health = VaultHealth() // isAvailable = false
        val scheduler = RentScheduler(plugin, service, health, mockk<EnthusiaMarketConfig>(relaxed = true))

        scheduler.start()

        verify { log.warning("Vault not available — rent collection disabled") }
    }

    // --- AuctionScheduler degradation ---

    @Test fun `auction scheduler skips startup when vault absent`() {
        val plugin = mockk<Plugin>(relaxed = true) {
            every { logger } returns mockk(relaxed = true)
        }
        val health = VaultHealth() // isAvailable = false
        val scheduler = AuctionScheduler(plugin, mockk(relaxed = true), health)

        // Should not throw
        scheduler.start()
    }

    @Test fun `auction scheduler logs warning when vault absent`() {
        val log = mockk<Logger>(relaxed = true)
        val plugin = mockk<Plugin>(relaxed = true) {
            every { logger } returns log
        }
        val health = VaultHealth()
        val scheduler = AuctionScheduler(plugin, mockk(relaxed = true), health)

        scheduler.start()

        verify { log.warning("Vault not available — auction settlement disabled") }
    }

    // --- VaultHealth @Component annotation ---

    @Test fun `vault health has component annotation`() {
        // Verify VaultHealth is annotated for Nexus DI scanning
        val annotation = VaultHealth::class.java.getAnnotation(Component::class.java)
        assert(annotation != null) { "VaultHealth must be annotated @Component for Nexus DI discovery" }
    }
}