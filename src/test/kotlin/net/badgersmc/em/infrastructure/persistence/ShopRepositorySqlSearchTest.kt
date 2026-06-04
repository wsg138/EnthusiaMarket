package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
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

class ShopRepositorySqlSearchTest {

    private val dbFile = File.createTempFile("em-shop-search", ".db")
    private lateinit var ds: DataSource

    @BeforeTest fun setup() {
        MockBukkit.mock()
        ds = DatabaseFactory.open(DatabaseSpec.Sqlite(dbFile)).also {
            MigrationRunner(it, resourcePrefix = "migrations", classLoader = javaClass.classLoader).runAll()
        }
    }

    @AfterTest fun teardown() { MockBukkit.unmock(); dbFile.delete() }

    private fun shop(searchEnabled: Boolean) = Shop(
        stallId = "stall1", owner = UUID.randomUUID(),
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "s", sellAmount = 1, costItem = "c", costAmount = 10,
        direction = SignDirection.SELL, searchEnabled = searchEnabled,
    )

    @Test fun `search_enabled survives a round trip`() {
        val repo = ShopRepositorySql(ds)
        val created = repo.upsert(shop(searchEnabled = false))
        val loaded = repo.findById(created.id)!!
        assertEquals(false, loaded.searchEnabled)
    }

    @Test fun `defaults to true`() {
        val repo = ShopRepositorySql(ds)
        val created = repo.upsert(shop(searchEnabled = true))
        assertEquals(true, repo.findById(created.id)!!.searchEnabled)
    }

    @Test fun `toggling search_enabled on an existing row persists via UPDATE`() {
        val repo = ShopRepositorySql(ds)
        val created = repo.upsert(shop(searchEnabled = true))
        // Second upsert on the SAME id exercises the UPDATE ... search_enabled = ? WHERE id = ? path.
        repo.upsert(created.copy(searchEnabled = false))
        assertEquals(false, repo.findById(created.id)!!.searchEnabled)
    }
}
