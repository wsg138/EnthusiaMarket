package net.badgersmc.em.infrastructure.moderation

import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdbcTransactionTest {
    @Test
    fun `duplicate classification excludes foreign key failures`() {
        assertTrue(SQLException("duplicate", "23000", 1062).isDuplicateKeyViolation())
        assertTrue(
            SQLiteException(
                "duplicate",
                SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY,
            ).isDuplicateKeyViolation(),
        )
        assertFalse(SQLException("foreign key", "23000", 1452).isDuplicateKeyViolation())
        assertFalse(
            SQLiteException(
                "foreign key",
                SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY,
            ).isDuplicateKeyViolation(),
        )
    }

    @Test
    fun `contention classification includes MariaDB lock timeout`() {
        assertTrue(SQLException("lock timeout", "HY000", 1205).isTransactionContention())
        assertTrue(SQLException("deadlock", "40001", 1213).isTransactionContention())
        assertFalse(SQLException("syntax", "42000", 1064).isTransactionContention())
    }
}
