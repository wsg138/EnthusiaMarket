package net.badgersmc.em.websync

import javax.sql.DataSource

class WebsiteSyncMigrationRunner(
    private val dataSource: DataSource,
    private val classLoader: ClassLoader = WebsiteSyncMigrationRunner::class.java.classLoader,
) {
    @Suppress("NestedBlockDepth")
    fun runAll() {
        dataSource.connection.use { connection ->
            val priorAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS em_websync_migrations " +
                            "(version_number INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)"
                    )
                }
                val applied = connection.prepareStatement(
                    "SELECT version_number FROM em_websync_migrations WHERE version_number = ?"
                ).use { statement ->
                    statement.setInt(1, 1)
                    statement.executeQuery().use { it.next() }
                }
                if (!applied) {
                    val sql = classLoader.getResourceAsStream("website-sync-migrations/V001__website_sync.sql")
                        ?.bufferedReader()?.use { it.readText() } ?: error("Website sync migration is missing")
                    sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach { command ->
                        connection.createStatement().use { it.executeUpdate(command) }
                    }
                    connection.prepareStatement(
                        "INSERT INTO em_websync_migrations (version_number, applied_at) VALUES (?, ?)"
                    ).use {
                        it.setInt(1, 1); it.setLong(2, System.currentTimeMillis()); it.executeUpdate()
                    }
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = priorAutoCommit
            }
        }
    }
}
