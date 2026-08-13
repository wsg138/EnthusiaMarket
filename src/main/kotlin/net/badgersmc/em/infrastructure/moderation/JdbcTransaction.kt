package net.badgersmc.em.infrastructure.moderation

import java.sql.Connection
import java.sql.SQLException
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

internal fun SQLException.isConstraintViolation(): Boolean =
    sqlState?.startsWith("23") == true ||
        message.orEmpty().contains("constraint", ignoreCase = true) ||
        message.orEmpty().contains("unique", ignoreCase = true)

internal fun SQLException.isTransactionContention(): Boolean =
    sqlState == "40001" ||
        message.orEmpty().contains("deadlock", ignoreCase = true) ||
        message.orEmpty().contains("database is locked", ignoreCase = true) ||
        message.orEmpty().contains("lock wait timeout", ignoreCase = true)
