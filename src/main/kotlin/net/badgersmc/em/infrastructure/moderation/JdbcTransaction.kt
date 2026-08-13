package net.badgersmc.em.infrastructure.moderation

import java.sql.Connection
import javax.sql.DataSource

internal inline fun <T> DataSource.inTransaction(block: (Connection) -> T): T = connection.use { connection ->
    val originalAutoCommit = connection.autoCommit
    connection.autoCommit = false
    try {
        val result = block(connection)
        connection.commit()
        result
    } catch (failure: Exception) {
        runCatching { connection.rollback() }.onFailure(failure::addSuppressed)
        throw failure
    } finally {
        connection.autoCommit = originalAutoCommit
    }
}
