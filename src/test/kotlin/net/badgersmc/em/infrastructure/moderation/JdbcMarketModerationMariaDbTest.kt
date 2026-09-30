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
import kotlin.test.assertFalse
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
        MarketMariaDbBaseline.create(dataSource, now)
        val applied = MigrationRunner(dataSource, "migrations", javaClass.classLoader).runAll()
        assertEquals(listOf(28, 29), applied.map { it.version })
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
            val statuses = results.map { it.get(10, TimeUnit.SECONDS).status() }

            assertEquals(1, statuses.count { it == MarketOperationResult.Status.PREPARED })
            assertEquals(1, statuses.count { it == MarketOperationResult.Status.CONFLICT })
            assertEquals(1, scalarInt("SELECT COUNT(*) FROM market_moderation_locks"))
            assertEquals(1, scalarInt("SELECT COUNT(*) FROM market_moderation_operations"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `mariadb preparation preserves an ordinary mutation already in flight`() {
        val mutationReady = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val preparationStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val mutation = pool.submit {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        connection.prepareStatement(
                            "UPDATE stalls SET winning_bid = 7777 WHERE id = 'stall-maria'",
                        ).use { assertEquals(1, it.executeUpdate()) }
                        connection.prepareStatement(
                            "UPDATE shop_items SET stock_count = 37 WHERE stall_id = 'stall-maria'",
                        ).use { assertEquals(1, it.executeUpdate()) }
                        mutationReady.countDown()
                        assertTrue(allowCommit.await(10, TimeUnit.SECONDS))
                        connection.commit()
                    } catch (failure: Exception) {
                        connection.rollback()
                        throw failure
                    }
                }
            }
            assertTrue(mutationReady.await(10, TimeUnit.SECONDS))

            val preparation = pool.submit<MarketOperationResult> {
                preparationStarted.countDown()
                store().prepare(request())
            }
            assertTrue(preparationStarted.await(10, TimeUnit.SECONDS))
            Thread.sleep(PREPARATION_BLOCK_CHECK_MILLIS)
            assertFalse(preparation.isDone)

            allowCommit.countDown()
            mutation.get(10, TimeUnit.SECONDS)
            val prepared = preparation.get(10, TimeUnit.SECONDS).operation().orElseThrow()
            val released = store().release(prepared.operationId(), prepared.snapshotChecksum())

            assertEquals(MarketOperationResult.Status.RELEASED, released.status())
            assertEquals("7777", scalarString("SELECT winning_bid FROM stalls WHERE id = 'stall-maria'"))
            assertEquals(37, scalarInt("SELECT stock_count FROM shop_items WHERE stall_id = 'stall-maria'"))
        } finally {
            allowCommit.countDown()
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
            assertTrue(entered.await(10, TimeUnit.SECONDS))

            val blocked = store().prepare(request())
            release.countDown()
            acquisition.get(10, TimeUnit.SECONDS)
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
        const val PREPARATION_BLOCK_CHECK_MILLIS = 200L

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
