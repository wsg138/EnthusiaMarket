package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
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
        MockBukkit.mock()
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite::memory:"
            maximumPoolSize = 1
        })
        net.badgersmc.nexus.persistence.MigrationRunner(ds, "migrations", this::class.java.classLoader).runAll()
        repo = ShopRepositorySql(ds)
    }

    @AfterEach
    fun tearDown() { ds.close(); MockBukkit.unmock() }

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

    @Test fun `guildId and creatorId round-trip through V005 columns`() {
        val guildId = UUID.randomUUID()
        val creatorId = UUID.randomUUID()
        val shop = Shop(
            stallId = "stall_guild",
            owner = UUID.randomUUID(),
            signWorld = "world", signX = 200, signY = 64, signZ = 300,
            containerWorld = "world", containerX = 201, containerY = 64, containerZ = 301,
            sellItem = "item", sellAmount = 1,
            costItem = "cost", costAmount = 5,
            guildId = guildId,
            creatorId = creatorId
        )
        val created = repo.upsert(shop)
        assertTrue(created.id > 0)
        assertEquals(guildId, created.guildId)
        assertEquals(creatorId, created.creatorId)

        val found = repo.findById(created.id)
        assertNotNull(found)
        assertEquals(guildId, found.guildId)
        assertEquals(creatorId, found.creatorId)
    }

    @Test fun `findByGuildId returns all shops for a given guild`() {
        val guildId = UUID.randomUUID()
        val owner = UUID.randomUUID()
        repeat(3) { i ->
            repo.upsert(Shop(
                stallId = "stall_guild_$i",
                owner = owner,
                signWorld = "world", signX = i * 10, signY = 64, signZ = i * 20,
                containerWorld = "world", containerX = 1, containerY = 64, containerZ = 1,
                sellItem = "item$i", sellAmount = 1,
                costItem = "cost", costAmount = 5,
                guildId = guildId,
                creatorId = UUID.randomUUID()
            ))
        }
        // Add a shop with a different guild
        repo.upsert(Shop(
            stallId = "stall_other_guild",
            owner = owner,
            signWorld = "world", signX = 99, signY = 64, signZ = 99,
            containerWorld = "world", containerX = 2, containerY = 64, containerZ = 2,
            sellItem = "other", sellAmount = 1,
            costItem = "cost", costAmount = 5,
            guildId = UUID.randomUUID(),
            creatorId = UUID.randomUUID()
        ))
        val shops = repo.findByGuildId(guildId)
        assertEquals(3, shops.size)
        shops.forEach { assertEquals(guildId, it.guildId) }
    }

    @Test fun `setGuildOwnership updates guild_id and creator_id`() {
        val shop = repo.upsert(Shop(
            stallId = "stall_ownership",
            owner = UUID.randomUUID(),
            signWorld = "world", signX = 50, signY = 64, signZ = 60,
            containerWorld = "world", containerX = 51, containerY = 64, containerZ = 61,
            sellItem = "item", sellAmount = 1,
            costItem = "cost", costAmount = 5
        ))
        assertNull(shop.guildId)
        assertNull(shop.creatorId)

        val guildId = UUID.randomUUID()
        val creatorId = UUID.randomUUID()
        val updated = repo.setGuildOwnership(shop.id, guildId, creatorId)
        assertNotNull(updated)
        assertEquals(guildId, updated.guildId)
        assertEquals(creatorId, updated.creatorId)
    }

    @Test fun `removeGuildOwnership clears guild_id and creator_id`() {
        val guildId = UUID.randomUUID()
        val creatorId = UUID.randomUUID()
        val shop = repo.upsert(Shop(
            stallId = "stall_remove",
            owner = UUID.randomUUID(),
            signWorld = "world", signX = 60, signY = 64, signZ = 70,
            containerWorld = "world", containerX = 61, containerY = 64, containerZ = 71,
            sellItem = "item", sellAmount = 1,
            costItem = "cost", costAmount = 5,
            guildId = guildId,
            creatorId = creatorId
        ))
        assertEquals(guildId, shop.guildId)
        assertEquals(creatorId, shop.creatorId)

        val cleared = repo.removeGuildOwnership(shop.id)
        assertNotNull(cleared)
        assertNull(cleared.guildId)
        assertNull(cleared.creatorId)
    }

    @Test fun `upsert stores sell_material derived from the sell item`() {
        val diamondB64 = ItemStackSerializer.serialize(org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND))
        val shop = Shop(
            stallId = "stall_mat",
            owner = UUID.randomUUID(),
            signWorld = "world", signX = 1, signY = 2, signZ = 3,
            containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
            sellItem = diamondB64, sellAmount = 1,
            costItem = "cost", costAmount = 10,
            direction = SignDirection.SELL, searchEnabled = true,
        )
        val created = repo.upsert(shop)
        val found = repo.findBySellMaterial("DIAMOND")
        assertEquals(listOf(created.id), found.map { it.id })
    }

    @Test fun `findBySellMaterial excludes search-disabled shops`() {
        val diamondB64 = ItemStackSerializer.serialize(org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND))
        val shop = Shop(
            stallId = "stall_disabled",
            owner = UUID.randomUUID(),
            signWorld = "world", signX = 1, signY = 2, signZ = 3,
            containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
            sellItem = diamondB64, sellAmount = 1,
            costItem = "cost", costAmount = 10,
            direction = SignDirection.SELL, searchEnabled = false,
        )
        repo.upsert(shop)
        assertTrue(repo.findBySellMaterial("DIAMOND").isEmpty())
    }

    @Test fun `stockCount defaults to 0`() {
        val shop = Shop(
            stallId = "stall_sc",
            owner = UUID.randomUUID(),
            signWorld = "world", signX = 1, signY = 2, signZ = 3,
            containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
            sellItem = "item", sellAmount = 1,
            costItem = "cost", costAmount = 5,
        )
        assertEquals(0, shop.stockCount)
    }

    @Test fun `backfill fills null sell_material rows`() {
        // Insert a row directly with sell_material left NULL (pre-V018 style)
        val diamondB64 = ItemStackSerializer.serialize(org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND))
        ds.connection.use { conn ->
            conn.prepareStatement(
                """INSERT INTO shop_items
                (stall_id, owner, sign_world, sign_x, sign_y, sign_z,
                 container_world, container_x, container_y, container_z,
                 sell_item, sell_amount, cost_item, cost_amount,
                 trusted, hopper_allow_in, hopper_allow_out, frozen, admin_shop,
                 guild_id, creator_id, direction, search_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { ps ->
                val shop = Shop(
                    stallId = "stall_bf",
                    owner = UUID.randomUUID(),
                    signWorld = "world", signX = 1, signY = 2, signZ = 3,
                    containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
                    sellItem = diamondB64, sellAmount = 1,
                    costItem = "cost", costAmount = 10,
                    direction = SignDirection.SELL, searchEnabled = true,
                )
                ps.setString(1, shop.stallId)
                ps.setString(2, shop.owner.toString())
                ps.setString(3, shop.signWorld); ps.setInt(4, shop.signX); ps.setInt(5, shop.signY); ps.setInt(6, shop.signZ)
                ps.setString(7, shop.containerWorld); ps.setInt(8, shop.containerX); ps.setInt(9, shop.containerY); ps.setInt(10, shop.containerZ)
                ps.setString(11, shop.sellItem); ps.setInt(12, shop.sellAmount)
                ps.setString(13, shop.costItem); ps.setInt(14, shop.costAmount)
                ps.setString(15, "")
                ps.setBoolean(16, true); ps.setBoolean(17, true); ps.setBoolean(18, false); ps.setBoolean(19, false)
                ps.setNull(20, java.sql.Types.VARCHAR); ps.setNull(21, java.sql.Types.VARCHAR)
                ps.setString(22, shop.direction.name)
                ps.setBoolean(23, shop.searchEnabled)
                ps.executeUpdate()
            }
        }
        val updated = repo.backfillSellMaterials()
        assertTrue(updated > 0)
        val found = repo.findBySellMaterial("DIAMOND")
        assertEquals(1, found.size)
    }
}