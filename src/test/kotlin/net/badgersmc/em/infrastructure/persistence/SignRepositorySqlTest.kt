package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.shop.ShopSign
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.stall.StallId
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SignRepositorySqlTest {
    private lateinit var ds: HikariDataSource
    private lateinit var repo: SignRepositorySql

    @BeforeTest fun setUp() {
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite::memory:"
            maximumPoolSize = 1
        })
        Migrations.runAll(ds)
        repo = SignRepositorySql(ds)
    }

    @AfterTest fun tearDown() { ds.close() }

    private fun sampleSign(
        stallId: String = "stall_01",
        direction: SignDirection = SignDirection.BUY,
        itemKey: String = "minecraft:diamond",
        price: Long = 5L,
        signLoc: String = "world,100,64,200",
        containerLoc: String = "world,100,65,200"
    ) = ShopSign(
        id = 0L,
        stallId = StallId(stallId),
        direction = direction,
        itemKey = itemKey,
        price = price,
        signLocation = signLoc,
        containerLocation = containerLoc
    )

    @Test fun `create then findById round-trips`() {
        val sign = sampleSign()
        val id = repo.create(sign)
        val found = repo.bySignLocation("world,100,64,200")
        assertNotNull(found)
        assertEquals(id, found.id)
        assertEquals(StallId("stall_01"), found.stallId)
        assertEquals(SignDirection.BUY, found.direction)
        assertEquals("minecraft:diamond", found.itemKey)
        assertEquals(5L, found.price)
        assertEquals("world,100,64,200", found.signLocation)
        assertEquals("world,100,65,200", found.containerLocation)
    }

    @Test fun `unknown location returns null`() {
        assertNull(repo.bySignLocation("world,0,0,0"))
    }

    @Test fun `byStall returns all signs for stall`() {
        repo.create(sampleSign(signLoc = "world,1,1,1"))
        repo.create(sampleSign(signLoc = "world,2,2,2", direction = SignDirection.SELL))
        repo.create(sampleSign(stallId = "stall_02", signLoc = "world,3,3,3"))

        val signs = repo.byStall(StallId("stall_01"))
        assertEquals(2, signs.size)
    }

    @Test fun `save updates sign fields`() {
        val id = repo.create(sampleSign())
        val updated = sampleSign().copy(id = id, price = 10L, direction = SignDirection.SELL)
        repo.save(updated)

        val found = repo.bySignLocation("world,100,64,200")!!
        assertEquals(10L, found.price)
        assertEquals(SignDirection.SELL, found.direction)
    }

    @Test fun `delete removes sign`() {
        val id = repo.create(sampleSign())
        repo.delete(id)
        assertNull(repo.bySignLocation("world,100,64,200"))
    }
}
