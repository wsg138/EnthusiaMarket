@file:Suppress("InvalidPackageDeclaration")
package net.badgersmc.em.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Audit 2026-06-09, M-3. The config offers `database.type: mariadb`, but
 * `ON CONFLICT ... DO UPDATE` is SQLite-only syntax — MariaDB throws a
 * SQLException at first use. Repositories must stick to portable SQL
 * (e.g. UPDATE-then-INSERT) so both backends work.
 */
class SqlPortabilityTest {

    @Test
    fun `persistence repositories use no SQLite-only upsert syntax`() {
        Konsist.scopeFromProduction()
            .files
            .filter { it.packagee?.name == "net.badgersmc.em.infrastructure.persistence" }
            .assertTrue(additionalMessage = "ON CONFLICT is SQLite-only; the mariadb config option breaks on it — use a portable upsert") {
                !it.text.contains("ON CONFLICT")
            }
    }
}
