package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopRepositorySqlTest {
    private lateinit var ds: HikariDataSource
    private lateinit var repo: ShopRepository

    @BeforeEach
    fun setUp() {
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite::memory:"
            maximumPoolSize = 1
        })
        Migrations.runAll(ds)
        repo = ShopRepositorySql(ds)
    }

    @AfterEach
    fun tearDown() { ds.close() }

    @Test fun `upsert and findById round-trips`() {
        val shop = Shop(
            stallId = "stall_01",
            owner = UUID.randomUUID(),
            signWorld = "world",
            signX = 100, signY = 64, signZ = 200,
            containerWorld = "world",
            containerX = 101, containerY = 64, containerZ = 201,
            sellItem = "base64item1",
            sellAmount = 1,
            costItem = "base64cost1",
            costAmount = 10
        )
        val created = repo.upsert(shop)
        assertTrue(created.id > 0)

        val found = repo.findById(created.id)
        assertNotNull(found)
        assertEquals(shop.stallId, found.stallId)
        assertEquals(shop.owner, found.owner)
        assertEquals(shop.signX, found.signX)
        assertEquals(shop.sellItem, found.sellItem)
    }

    @Test fun `findBySign locates by sign coordinates`() {
        val shop = Shop(
            stallId = "stall_02",
            owner = UUID.randomUUID(),
            signWorld = "world", signX = 10, signY = 64, signZ = 20,
            containerWorld = "world", containerX = 11, containerY = 64, containerZ = 21,
            sellItem = "item", sellAmount = 1,
            costItem = "cost", costAmount = 5
        )
        repo.upsert(shop)

        val found = repo.findBySign("world", 10, 64, 20)
        assertNotNull(found)
        assertEquals("stall_02", found.stallId)
    }

    @Test fun `findByContainer returns all shops on a container`() {
        val owner = UUID.randomUUID()
        for (i in 1..3) {
            repo.upsert(Shop(
                stallId = "stall_0$i",
                owner = owner,
                signWorld = "world", signX = i*10, signY = 64, signZ = i*20,
                containerWorld = "world", containerX = 50, containerY = 64, containerZ = 60,
                sellItem = "item$i", sellAmount = 1,
                costItem = "cost", costAmount = 5
            ))
        }
        val shops = repo.findByContainer("world", 50, 64, 60)
        assertEquals(3, shops.size)
    }

    @Test fun `trusted UUIDs round-trip through serialization`() {
        val trusted = setOf(UUID.randomUUID(), UUID.randomUUID())
        val shop = Shop(
            stallId = "stall_03",
            owner = UUID.randomUUID(),
            signWorld = "world", signX = 30, signY = 64, signZ = 40,
            containerWorld = "world", containerX = 31, containerY = 64, containerZ = 41,
            sellItem = "item", sellAmount = 1,
            costItem = "cost", costAmount = 5,
            trusted = trusted
        )
        val created = repo.upsert(shop)
        val found = repo.findById(created.id)
        assertNotNull(found)
        assertEquals(trusted, found.trusted)
    }

    @Test fun `delete removes shop`() {
        val shop = repo.upsert(Shop(
            stallId = "stall_04",
            owner = UUID.randomUUID(),
            signWorld = "world", signX = 40, signY = 64, signZ = 50,
            containerWorld = "world", containerX = 41, containerY = 64, containerZ = 51,
            sellItem = "item", sellAmount = 1,
            costItem = "cost", costAmount = 5
        ))
        assertNotNull(repo.findById(shop.id))
        repo.delete(shop.id)
        assertNull(repo.findById(shop.id))
    }

    @Test fun `deleteByContainer removes all shops on container`() {
        val owner = UUID.randomUUID()
        repeat(2) {
            repo.upsert(Shop(
                stallId = "stall_${it + 10}",
                owner = owner,
                signWorld = "world", signX = it * 100, signY = 64, signZ = it * 200,
                containerWorld = "world", containerX = 99, containerY = 64, containerZ = 99,
                sellItem = "item$it", sellAmount = 1,
                costItem = "cost", costAmount = 5
            ))
        }
        assertEquals(2, repo.findByContainer("world", 99, 64, 99).size)
        repo.deleteByContainer("world", 99, 64, 99)
        assertTrue(repo.findByContainer("world", 99, 64, 99).isEmpty())
    }

    @Test fun `findByStall returns all shops in a stall`() {
        val owner = UUID.randomUUID()
        repeat(2) {
            repo.upsert(Shop(
                stallId = "stall_big",
                owner = owner,
                signWorld = "world", signX = it * 10, signY = 64, signZ = it * 20,
                containerWorld = "world", containerX = 1, containerY = 64, containerZ = 1,
                sellItem = "item$it", sellAmount = 1,
                costItem = "cost", costAmount = 5
            ))
        }
        assertEquals(2, repo.findByStall("stall_big").size)
    }

    @Test fun `all returns every shop`() {
        repeat(3) {
            repo.upsert(Shop(
                stallId = "stall_all",
                owner = UUID.randomUUID(),
                signWorld = "world", signX = it * 50, signY = 64, signZ = it * 100,
                containerWorld = "world", containerX = 0, containerY = 64, containerZ = 0,
                sellItem = "item$it", sellAmount = 1,
                costItem = "cost", costAmount = 5
            ))
        }
        assertTrue(repo.all().size >= 3)
    }

    @Test fun `findByOwner returns shops for a given owner`() {
        val owner = UUID.randomUUID()
        repeat(2) {
            repo.upsert(Shop(
                stallId = "stall_owner_$it",
                owner = owner,
                signWorld = "world", signX = it * 10, signY = 64, signZ = it * 20,
                containerWorld = "world", containerX = 1, containerY = 64, containerZ = 1,
                sellItem = "item$it", sellAmount = 1,
                costItem = "cost", costAmount = 5
            ))
        }
        // Add another shop with a different owner
        repo.upsert(Shop(
            stallId = "stall_other",
            owner = UUID.randomUUID(),
            signWorld = "world", signX = 99, signY = 64, signZ = 99,
            containerWorld = "world", containerX = 2, containerY = 64, containerZ = 2,
            sellItem = "other", sellAmount = 1,
            costItem = "cost", costAmount = 5
        ))
        assertEquals(2, repo.findByOwner(owner).size)
    }

    @Test fun `upsert with existing id updates the record`() {
        val shop = Shop(
            stallId = "stall_update",
            owner = UUID.randomUUID(),
            signWorld = "world", signX = 50, signY = 64, signZ = 60,
            containerWorld = "world", containerX = 51, containerY = 64, containerZ = 61,
            sellItem = "original", sellAmount = 1,
            costItem = "cost", costAmount = 5
        )
        val created = repo.upsert(shop)
        val updated = created.copy(sellItem = "updated", sellAmount = 3)
        val result = repo.upsert(updated)
        assertEquals(created.id, result.id)
        val found = repo.findById(created.id)!!
        assertEquals("updated", found.sellItem)
        assertEquals(3, found.sellAmount)
    }
}