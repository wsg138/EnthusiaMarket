package net.badgersmc.em.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import org.junit.jupiter.api.Test

/**
 * SPEAR layer-rule enforcement (REQ-101).
 *
 * Three-layer hexagonal discipline: domain depends on nothing,
 * application depends only on domain, infrastructure is unconstrained.
 *
 * Framework-annotation denylist is enforced by `/spear:arch`
 * against `docs/implementation.md` §"Forbidden Domain Annotations".
 */
class LayerRulesTest {

    @Test
    fun `spear layer dependencies are correct`() {
        Konsist.scopeFromProduction().assertArchitecture {
            val domain = Layer("Domain", "net.badgersmc.em.domain..")
            val application = Layer("Application", "net.badgersmc.em.application..")
            val infrastructure = Layer("Infrastructure", "net.badgersmc.em.infrastructure..")

            domain.dependsOnNothing()
            application.dependsOn(domain)
            infrastructure.dependsOn(domain, application)
        }
    }
}
