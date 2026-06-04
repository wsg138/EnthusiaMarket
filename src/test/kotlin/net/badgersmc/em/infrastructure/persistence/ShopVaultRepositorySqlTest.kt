package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.nexus.persistence.DatabaseFactory
import net.badgersmc.nexus.persistence.DatabaseSpec
import net.badgersmc.nexus.persistence.MigrationRunner
import org.mockbukkit.mockbukkit.MockBukkit
import java.io.File
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopVaultRepositorySqlTest {

    private val dbFile = File.createTempFile("em-vault", ".db")
    private lateinit var ds: DataSource

    @BeforeTest fun setup() {
        MockBukkit.mock()
        ds = DatabaseFactory.open(DatabaseSpec.Sqlite(dbFile)).also {
            MigrationRunner(it, resourcePrefix = "migrations", classLoader = javaClass.classLoader).runAll()
        }
    }

    @AfterTest fun teardown() { MockBukkit.unmock(); dbFile.delete() }

    @Test fun `deposit aggregates the same item key`() {
        val repo = ShopVaultRepositorySql(ds)
        val owner = UUID.randomUUID()
        repo.deposit(owner, "DIAMOND_KEY", 5)
        repo.deposit(owner, "DIAMOND_KEY", 3)
        val rows = repo.findByOwner(owner)
        assertEquals(1, rows.size)
        assertEquals(8, rows.first().amount)
    }

    @Test fun `different items are separate rows`() {
        val repo = ShopVaultRepositorySql(ds)
        val owner = UUID.randomUUID()
        repo.deposit(owner, "A", 1); repo.deposit(owner, "B", 1)
        assertEquals(2, repo.findByOwner(owner).size)
    }

    @Test fun `withdraw decrements and deletes at zero`() {
        val repo = ShopVaultRepositorySql(ds)
        val owner = UUID.randomUUID()
        repo.deposit(owner, "A", 10)
        assertEquals(4, repo.withdraw(owner, "A", 4))
        assertEquals(6, repo.findByOwner(owner).first().amount)
        assertEquals(6, repo.withdraw(owner, "A", 100)) // only 6 left
        assertEquals(0, repo.findByOwner(owner).size)
    }
}
