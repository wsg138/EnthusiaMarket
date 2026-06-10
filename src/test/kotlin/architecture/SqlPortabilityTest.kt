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
        val sqliteOnly = listOf("ON CONFLICT", "INSERT OR REPLACE", "INSERT OR IGNORE")
        Konsist.scopeFromProduction()
            .files
            .filter { it.packagee?.name == "net.badgersmc.em.infrastructure.persistence" }
            .assertTrue(additionalMessage = "$sqliteOnly are SQLite-only; the mariadb config option breaks on them — use a portable upsert") { file ->
                sqliteOnly.none { file.text.contains(it) }
            }
    }
}
