package net.badgersmc.em.infrastructure.persistence

import java.time.Instant
import javax.sql.DataSource

object Migrations {
    private data class M(val version: Int, val resource: String)

    private val ALL = listOf(
        M(1, "/migrations/V001__init.sql"),
        M(2, "/migrations/V002__shop_signs.sql")
    )

    fun runAll(ds: DataSource) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().use {
                it.execute(
                    """CREATE TABLE IF NOT EXISTS schema_version (
                          version INTEGER PRIMARY KEY,
                          applied_at INTEGER NOT NULL
                       )""".trimIndent()
                )
            }
            val applied = mutableSetOf<Int>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT version FROM schema_version").use { rs ->
                    while (rs.next()) applied.add(rs.getInt(1))
                }
            }
            for (m in ALL) {
                if (m.version in applied) continue
                val sql = Migrations::class.java.getResource(m.resource)?.readText()
                    ?: error("Missing migration: ${m.resource}")
                for (stmt in sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
                    conn.createStatement().use { it.execute(stmt) }
                }
                conn.prepareStatement(
                    "INSERT INTO schema_version(version, applied_at) VALUES (?, ?)"
                ).use {
                    it.setInt(1, m.version)
                    it.setLong(2, Instant.now().toEpochMilli())
                    it.executeUpdate()
                }
            }
            conn.commit()
        }
    }
}
