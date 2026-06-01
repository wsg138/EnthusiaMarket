package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.stall.*
import net.badgersmc.nexus.persistence.DatabaseFactory
import net.badgersmc.nexus.persistence.DatabaseSpec
import net.badgersmc.nexus.persistence.MigrationRunner
import java.io.File
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.AfterTest

class StallRepositorySqlKindTest {

    private val dbFile = File.createTempFile("em-kind-test", ".db")
    private val ds: DataSource = DatabaseFactory.open(DatabaseSpec.Sqlite(dbFile)).also {
        MigrationRunner(it, resourcePrefix = "migrations", classLoader = javaClass.classLoader).runAll()
    }

    @AfterTest fun cleanup() { dbFile.delete() }

    @Test fun `kind and extras survive a create-read round trip`() {
        val repo = StallRepositorySql(ds)
        val stall = Stall(
            id = StallId("stall1"), regionId = "stall1", world = "world",
            state = StallState.UNOWNED, owner = OwnerRef.unowned(),
            ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
            kind = "shop", extraEntities = mapOf("villager" to 3, "armor_stand" to 2),
            extraTotal = 10,
        )
        repo.create(stall)
        val loaded = repo.findById(StallId("stall1"))!!
        assertEquals("shop", loaded.kind)
        assertEquals(3, loaded.extraEntities["villager"])
        assertEquals(2, loaded.extraEntities["armor_stand"])
        assertEquals(10, loaded.extraTotal)
    }

    @Test fun `defaults apply for a minimal stall`() {
        val repo = StallRepositorySql(ds)
        repo.create(Stall(
            id = StallId("stall2"), regionId = "stall2", world = "world",
            state = StallState.UNOWNED, owner = OwnerRef.unowned(),
            ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
        ))
        val loaded = repo.findById(StallId("stall2"))!!
        assertEquals("default", loaded.kind)
        assertEquals(emptyMap(), loaded.extraEntities)
        assertEquals(0, loaded.extraTotal)
    }
}
