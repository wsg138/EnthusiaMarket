package net.badgersmc.em.infrastructure.moderation

import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
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

internal fun SQLException.isDuplicateKeyViolation(): Boolean = when (this) {
    is SQLiteException -> resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY ||
        resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
    else -> sqlState == MARIADB_INTEGRITY_STATE && errorCode == MARIADB_DUPLICATE_KEY
}

internal fun SQLException.isTransactionContention(): Boolean = when (this) {
    is SQLiteException -> resultCode in sqliteContentionCodes
    else -> sqlState == TRANSACTION_ROLLBACK_STATE || errorCode in mariaDbContentionCodes
}

private const val MARIADB_INTEGRITY_STATE = "23000"
private const val TRANSACTION_ROLLBACK_STATE = "40001"
private const val MARIADB_DUPLICATE_KEY = 1062
private val mariaDbContentionCodes = setOf(1205, 1213)
private val sqliteContentionCodes = setOf(
    SQLiteErrorCode.SQLITE_BUSY,
    SQLiteErrorCode.SQLITE_BUSY_RECOVERY,
    SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT,
    SQLiteErrorCode.SQLITE_BUSY_TIMEOUT,
    SQLiteErrorCode.SQLITE_LOCKED,
    SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE,
    SQLiteErrorCode.SQLITE_LOCKED_VTAB,
)
