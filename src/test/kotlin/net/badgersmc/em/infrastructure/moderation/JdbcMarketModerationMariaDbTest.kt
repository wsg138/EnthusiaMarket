package net.badgersmc.em.infrastructure.moderation

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.ports.MarketAcquisitionBlockedException
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.infrastructure.persistence.StallRepositorySql
import net.badgersmc.nexus.persistence.MigrationRunner
import net.enthusia.market.api.moderation.MarketBlacklistRequest
import net.enthusia.market.api.moderation.MarketBlacklistResult
import net.enthusia.market.api.moderation.MarketOperationRequest
import net.enthusia.market.api.moderation.MarketOperationResult
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class JdbcMarketModerationMariaDbTest {
    private lateinit var dataSource: HikariDataSource
    private val now = Instant.parse("2026-08-13T12:00:00Z")
    private val ownerId = UUID.fromString("42e4d998-71bc-469c-9465-9fcd09a3fd4b")

    @BeforeTest
    fun setUp() {
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = database.jdbcUrl
            username = database.username
            password = database.password
            maximumPoolSize = 4
        })
        createUpgradeBaseline()
        val applied = MigrationRunner(dataSource, "migrations", javaClass.classLoader).runAll()
        assertEquals(listOf(25), applied.map { it.version })
        createStallAndShop()
    }

    @AfterTest
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `mariadb lifecycle restores ownership and releases durable fences`() {
        val store = store()
        val prepared = store.prepare(request()).operation().orElseThrow()
        val held = store.confiscate(
            net.enthusia.market.api.moderation.MarketConfiscationApproval(
                prepared.operationId(),
                UUID.randomUUID(),
                prepared.snapshotChecksum(),
                now.plusSeconds(DAY_SECONDS),
            ),
        ).operation().orElseThrow()

        val restored = store.restore(
            net.enthusia.market.api.moderation.MarketRestoreRequest(
                held.operationId(),
                UUID.randomUUID(),
                held.currentChecksum().orElseThrow(),
            ),
        )

        assertEquals(MarketOperationResult.Status.RESTORED, restored.status())
        assertEquals("SOLO", scalarString("SELECT owner_type FROM stalls WHERE id = 'stall-maria'"))
        assertEquals(ownerId.toString(), scalarString("SELECT owner_id FROM stalls WHERE id = 'stall-maria'"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM market_moderation_locks"))
        assertEquals(0, scalarInt("SELECT frozen FROM shop_items WHERE stall_id = 'stall-maria'"))
    }

    @Test
    fun `mariadb concurrent preparations produce one durable winner`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("CASE-A", "CASE-B").map { caseId ->
                pool.submit<MarketOperationResult> {
                    start.await()
                    store().prepare(request(UUID.randomUUID(), caseId))
                }
            }
            start.countDown()
            val statuses = results.map { it.get().status() }

            assertEquals(1, statuses.count { it == MarketOperationResult.Status.PREPARED })
            assertEquals(1, statuses.count { it == MarketOperationResult.Status.CONFLICT })
            assertEquals(1, scalarInt("SELECT COUNT(*) FROM market_moderation_locks"))
            assertEquals(1, scalarInt("SELECT COUNT(*) FROM market_moderation_operations"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `mariadb acquisition permit wins or moderation wins without overlap`() {
        val policy = JdbcMarketModerationPolicy(dataSource, Clock.fixed(now, ZoneOffset.UTC))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val acquisition = pool.submit {
                policy.withAcquisitionPermit(ownerId) {
                    entered.countDown()
                    release.await()
                }
            }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))

            val blocked = store().prepare(request())
            release.countDown()
            acquisition.get()
            val prepared = store().prepare(request())

            assertEquals(MarketOperationResult.Status.CONFLICT, blocked.status())
            assertEquals(MarketOperationResult.Status.PREPARED, prepared.status())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `mariadb acquisition and blacklist claims never overlap`() {
        val policy = JdbcMarketModerationPolicy(dataSource, Clock.fixed(now, ZoneOffset.UTC))
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(CONCURRENCY_ROUNDS) {
                val playerId = UUID.randomUUID()
                val start = CountDownLatch(1)
                val releaseAcquisition = CountDownLatch(1)
                val acquisition = pool.submit<Boolean> {
                    start.await()
                    try {
                        policy.withAcquisitionPermit(playerId) {
                            releaseAcquisition.await(10, TimeUnit.SECONDS)
                        }
                        true
                    } catch (_: MarketAcquisitionBlockedException) {
                        false
                    }
                }
                val blacklist = pool.submit<MarketBlacklistResult.Status> {
                    start.await()
                    store().applyBlacklist(
                        MarketBlacklistRequest(UUID.randomUUID(), playerId, "CASE-RACE", Optional.empty()),
                    ).status()
                }

                start.countDown()
                val blacklistStatus = blacklist.get(10, TimeUnit.SECONDS)
                releaseAcquisition.countDown()
                val acquired = acquisition.get(10, TimeUnit.SECONDS)

                if (acquired) {
                    assertEquals(MarketBlacklistResult.Status.CONFLICT, blacklistStatus)
                } else {
                    assertEquals(MarketBlacklistResult.Status.APPLIED, blacklistStatus)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `mariadb concurrent blacklist applications produce one winner`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(CONCURRENCY_ROUNDS) {
                val playerId = UUID.randomUUID()
                val start = CountDownLatch(1)
                val results = listOf("CASE-A", "CASE-B").map { caseId ->
                    pool.submit<MarketBlacklistResult.Status> {
                        start.await()
                        store().applyBlacklist(
                            MarketBlacklistRequest(UUID.randomUUID(), playerId, caseId, Optional.empty()),
                        ).status()
                    }
                }
                start.countDown()
                val statuses = results.map { it.get(10, TimeUnit.SECONDS) }

                assertEquals(1, statuses.count { it == MarketBlacklistResult.Status.APPLIED })
                assertEquals(1, statuses.count { it == MarketBlacklistResult.Status.CONFLICT })
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun store() = JdbcMarketModerationStore(
        dataSource,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun request(
        operationId: UUID = UUID.fromString("d2f99854-c5dd-4a0d-b97d-642a28c04db8"),
        caseId: String = "CASE-MARIA",
    ) = MarketOperationRequest(
        operationId,
        ownerId,
        caseId,
        "stall-maria",
        now.plusSeconds(7 * DAY_SECONDS),
        now.plusSeconds(30 * DAY_SECONDS),
        Optional.empty(),
    )

    private fun createUpgradeBaseline() {
        dataSource.connection.use { connection ->
            listOf(
                "market_moderation_locks",
                "market_moderation_operations",
                "market_stall_blacklists",
                "market_player_fences",
                "shop_transactions",
                "shop_items",
                "stalls",
                "schema_migration",
            ).forEach { table -> connection.prepareStatement("DROP TABLE IF EXISTS $table").use { it.executeUpdate() } }
            createStallsBaseline(connection)
            createShopsBaseline(connection)
            createMigrationBaseline(connection)
        }
    }

    private fun createStallsBaseline(connection: java.sql.Connection) {
        connection.prepareStatement(
            """CREATE TABLE stalls (
                   id VARCHAR(128) PRIMARY KEY,
                   region_id VARCHAR(128) NOT NULL,
                   world VARCHAR(128) NOT NULL,
                   state VARCHAR(32) NOT NULL,
                   owner_type VARCHAR(16) NOT NULL,
                   owner_id VARCHAR(128) NOT NULL,
                   owner_since BIGINT,
                   winning_bid BIGINT NOT NULL DEFAULT 0,
                   rent_mode VARCHAR(16) NOT NULL,
                   rent_pct DOUBLE NOT NULL DEFAULT 0,
                   rent_flat BIGINT NOT NULL DEFAULT 0,
                   members TEXT NOT NULL,
                   max_members INTEGER NOT NULL DEFAULT -1,
                   next_rent_at BIGINT,
                   kind VARCHAR(64) NOT NULL DEFAULT 'default',
                   extra_entities TEXT NOT NULL,
                   extra_total INTEGER NOT NULL DEFAULT 0,
                   UNIQUE(world, region_id)
                )""",
        ).use { it.executeUpdate() }
    }

    private fun createShopsBaseline(connection: java.sql.Connection) {
        connection.prepareStatement(
            """CREATE TABLE shop_items (
                   id BIGINT PRIMARY KEY AUTO_INCREMENT,
                   stall_id VARCHAR(128) NOT NULL,
                   owner VARCHAR(36) NOT NULL,
                   sign_world VARCHAR(128) NOT NULL,
                   sign_x INTEGER NOT NULL,
                   sign_y INTEGER NOT NULL,
                   sign_z INTEGER NOT NULL,
                   container_world VARCHAR(128) NOT NULL,
                   container_x INTEGER NOT NULL,
                   container_y INTEGER NOT NULL,
                   container_z INTEGER NOT NULL,
                   sell_item TEXT NOT NULL,
                   sell_amount INTEGER NOT NULL DEFAULT 1,
                   cost_item TEXT NOT NULL,
                   cost_amount INTEGER NOT NULL DEFAULT 1,
                   trusted TEXT NOT NULL,
                   hopper_allow_in BOOLEAN NOT NULL DEFAULT TRUE,
                   hopper_allow_out BOOLEAN NOT NULL DEFAULT TRUE,
                   frozen BOOLEAN NOT NULL DEFAULT FALSE,
                   admin_shop BOOLEAN NOT NULL DEFAULT FALSE,
                   direction VARCHAR(16) NOT NULL DEFAULT 'SELL',
                   search_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                   sell_material VARCHAR(128),
                   stock_count INTEGER NOT NULL DEFAULT 0,
                   FOREIGN KEY (stall_id) REFERENCES stalls(id)
                )""",
        ).use { it.executeUpdate() }
    }

    private fun createMigrationBaseline(connection: java.sql.Connection) {
        connection.prepareStatement(
            """CREATE TABLE schema_migration (
                   version INTEGER PRIMARY KEY,
                   name VARCHAR(255) NOT NULL,
                   applied_at BIGINT NOT NULL
               )""",
        ).use { it.executeUpdate() }
        connection.prepareStatement(
            "INSERT INTO schema_migration(version, name, applied_at) VALUES (?, ?, ?)",
        ).use { statement ->
            (1..24).forEach { version ->
                statement.setInt(1, version)
                statement.setString(2, "baseline_$version")
                statement.setLong(3, now.toEpochMilli())
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun createStallAndShop() {
        StallRepositorySql(dataSource).create(
            Stall(
                StallId("stall-maria"),
                "market-stall-maria",
                "market",
                StallState.OWNED,
                OwnerRef.solo(ownerId),
                now.minusSeconds(DAY_SECONDS),
                4_000L,
                RentTerms.formula(0.05),
                nextRentAt = now.plusSeconds(DAY_SECONDS),
            ),
        )
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO shop_items
                   (stall_id, owner, sign_world, sign_x, sign_y, sign_z,
                    container_world, container_x, container_y, container_z,
                    sell_item, sell_amount, cost_item, cost_amount, trusted,
                    hopper_allow_in, hopper_allow_out, frozen, admin_shop,
                    direction, search_enabled, sell_material, stock_count)
                   VALUES ('stall-maria', ?, 'market', 1, 65, 1, 'market', 1, 64, 1,
                           'c2VsbA==', 1, 'Y29zdA==', 5, '', 1, 1, 0, 0, 'SELL', 1, 'DIAMOND', 10)""",
            ).use { statement ->
                statement.setString(1, ownerId.toString())
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun scalarString(sql: String): String = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getString(1)
            }
        }
    }

    private fun scalarInt(sql: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getInt(1)
            }
        }
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
        const val CONCURRENCY_ROUNDS = 8

        @Container
        @JvmStatic
        val database = MarketMariaDbContainer("mariadb:11.8.3")
            .withDatabaseName("enthusia_market_test")
            .withUsername("market_test")
            .withPassword("market_test")
    }

    private class MarketMariaDbContainer(image: String) :
        MariaDBContainer<MarketMariaDbContainer>(DockerImageName.parse(image))
}
