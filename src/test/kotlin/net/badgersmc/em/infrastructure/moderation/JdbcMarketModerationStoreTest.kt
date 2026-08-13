package net.badgersmc.em.infrastructure.moderation

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.infrastructure.persistence.StallRepositorySql
import net.badgersmc.em.infrastructure.persistence.MarketModerationConflictException
import net.badgersmc.em.infrastructure.persistence.ShopRepositorySql
import net.enthusia.market.api.moderation.MarketBlacklistRemoval
import net.enthusia.market.api.moderation.MarketBlacklistRequest
import net.enthusia.market.api.moderation.MarketBlacklistResult
import net.enthusia.market.api.moderation.MarketConfiscationApproval
import net.enthusia.market.api.moderation.MarketOperationRequest
import net.enthusia.market.api.moderation.MarketOperationResult
import net.enthusia.market.api.moderation.MarketRestoreRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcMarketModerationStoreTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var stallRepository: StallRepositorySql
    private lateinit var store: JdbcMarketModerationStore
    private val now = Instant.parse("2026-08-13T12:00:00Z")
    private val ownerId = UUID.fromString("5a1fbbe4-b27f-4c75-832a-63e4ad1b7e35")
    private val reviewerId = UUID.fromString("b703df70-8f85-4c10-b55a-1b51809b879a")

    @BeforeTest
    fun setUp() {
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite::memory:"
            maximumPoolSize = 1
        })
        net.badgersmc.nexus.persistence.MigrationRunner(
            dataSource,
            "migrations",
            javaClass.classLoader,
        ).runAll()
        stallRepository = StallRepositorySql(dataSource)
        store = storeAt(now)
        createOwnedStall()
        createShop()
    }

    @AfterTest
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `prepare is durable idempotent and never removes ownership on elapsed time`() {
        val request = request()

        val prepared = store.prepare(request)
        val replayedAfterRestart = storeAt(now.plusSeconds(8 * DAY_SECONDS)).prepare(request)

        assertEquals(MarketOperationResult.Status.PREPARED, prepared.status())
        assertEquals(MarketOperationResult.Status.REPLAYED, replayedAfterRestart.status())
        assertEquals(ownerId.toString(), stallValue("owner_id"))
        assertEquals("OWNED", stallValue("state"))
        assertTrue(shopFrozen())
        assertFalse(store.canAcquire(ownerId))
        assertEquals(prepared.operation().orElseThrow(), store.findOperation(request.operationId()).orElseThrow())
    }

    @Test
    fun `reviewed hold and restore preserve exact ownership and shop state`() {
        val prepared = store.prepare(request()).operation().orElseThrow()
        val heldResult = store.confiscate(
            MarketConfiscationApproval(
                prepared.operationId(),
                reviewerId,
                prepared.snapshotChecksum(),
                now.plusSeconds(DAY_SECONDS),
            ),
        )
        val held = heldResult.operation().orElseThrow()

        assertEquals(MarketOperationResult.Status.HELD, heldResult.status())
        assertEquals("MODERATION_HOLD", stallValue("state"))
        assertEquals("NONE", stallValue("owner_type"))
        assertEquals("", stallValue("owner_id"))
        assertTrue(shopFrozen())

        val restored = store.restore(
            MarketRestoreRequest(held.operationId(), reviewerId, held.currentChecksum().orElseThrow()),
        )

        assertEquals(MarketOperationResult.Status.RESTORED, restored.status())
        assertEquals("OWNED", stallValue("state"))
        assertEquals("SOLO", stallValue("owner_type"))
        assertEquals(ownerId.toString(), stallValue("owner_id"))
        assertFalse(shopFrozen())
        assertNull(store.getBlacklist(ownerId).orElse(null))
        assertTrue(store.canAcquire(ownerId))
        assertEquals(0, scalar("SELECT COUNT(*) FROM market_moderation_locks"))
    }

    @Test
    fun `release reverses preparation without entering a moderation hold`() {
        val prepared = store.prepare(request()).operation().orElseThrow()

        val released = store.release(prepared.operationId(), prepared.snapshotChecksum())

        assertEquals(MarketOperationResult.Status.RELEASED, released.status())
        assertEquals("OWNED", stallValue("state"))
        assertFalse(shopFrozen())
        assertTrue(store.canAcquire(ownerId))
    }

    @Test
    fun `stale restore checksum leaves the reviewed hold intact`() {
        val prepared = store.prepare(request()).operation().orElseThrow()
        val held = store.confiscate(approval(prepared)).operation().orElseThrow()

        val stale = store.restore(
            MarketRestoreRequest(held.operationId(), reviewerId, "0".repeat(64)),
        )

        assertEquals(MarketOperationResult.Status.CONFLICT, stale.status())
        assertEquals("MODERATION_HOLD", stallValue("state"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM market_moderation_locks"))
    }

    @Test
    fun `second operation cannot reserve an already prepared stall`() {
        val first = store.prepare(request())
        val second = store.prepare(request(operationId = UUID.randomUUID(), caseId = "CASE-OTHER"))

        assertEquals(MarketOperationResult.Status.PREPARED, first.status())
        assertEquals(MarketOperationResult.Status.CONFLICT, second.status())
        assertEquals(1, scalar("SELECT COUNT(*) FROM market_moderation_operations"))
    }

    @Test
    fun `tampered prepared state is quarantined instead of confiscated`() {
        val prepared = store.prepare(request()).operation().orElseThrow()
        execute("UPDATE shop_items SET frozen = 0 WHERE stall_id = 'stall-1'")

        val result = store.confiscate(
            MarketConfiscationApproval(
                prepared.operationId(),
                reviewerId,
                prepared.snapshotChecksum(),
                now.plusSeconds(DAY_SECONDS),
            ),
        )

        assertEquals(MarketOperationResult.Status.QUARANTINED, result.status())
        assertEquals("OWNED", stallValue("state"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM market_moderation_locks"))
    }

    @Test
    fun `tampered journal snapshot is quarantined before region access changes`() {
        val regions = RecordingRegionAccess()
        val provider = MarketModerationProvider(
            store,
            DurableMarketMutationGate(dataSource),
            regions,
            Executors.newSingleThreadExecutor(),
        )
        try {
            val prepared = provider.prepare(request()).toCompletableFuture().join().operation().orElseThrow()
            execute(
                "UPDATE market_moderation_operations SET snapshot_json = " +
                    "REPLACE(snapshot_json, 'market-stall-1', 'wrong-region')",
            )

            val result = provider.confiscate(approval(prepared)).toCompletableFuture().join()

            assertEquals(MarketOperationResult.Status.QUARANTINED, result.status())
            assertEquals(0, regions.clearCount)
            assertEquals("OWNED", stallValue("state"))
        } finally {
            provider.close()
        }
    }

    @Test
    fun `standalone blacklist uses revision checks and idempotent removal`() {
        val applyId = UUID.randomUUID()
        val applied = store.applyBlacklist(
            MarketBlacklistRequest(applyId, ownerId, "CASE-BLACKLIST", Optional.empty()),
        )
        val state = applied.blacklist().orElseThrow()
        val removalId = UUID.randomUUID()
        val removal = MarketBlacklistRemoval(removalId, ownerId, "CASE-BLACKLIST", state.revision())

        val removed = store.removeBlacklist(removal)
        val replayed = store.removeBlacklist(removal)

        assertEquals(MarketBlacklistResult.Status.APPLIED, applied.status())
        assertEquals(MarketBlacklistResult.Status.REMOVED, removed.status())
        assertEquals(MarketBlacklistResult.Status.REPLAYED, replayed.status())
        assertTrue(store.canAcquire(ownerId))
    }

    @Test
    fun `wrong blacklist revision preserves the active restriction`() {
        val applied = store.applyBlacklist(
            MarketBlacklistRequest(UUID.randomUUID(), ownerId, "CASE-BLACKLIST", Optional.empty()),
        ).blacklist().orElseThrow()

        val result = store.removeBlacklist(
            MarketBlacklistRemoval(UUID.randomUUID(), ownerId, "CASE-BLACKLIST", applied.revision() + 1L),
        )

        assertEquals(MarketBlacklistResult.Status.CONFLICT, result.status())
        assertFalse(store.canAcquire(ownerId))
    }

    @Test
    fun `snapshot safety limit rejects stalls with more than one hundred shops`() {
        repeat(100) { createShop(signX = it + 2) }

        val result = store.prepare(request())

        assertEquals(MarketOperationResult.Status.REJECTED, result.status())
        assertEquals(0, scalar("SELECT COUNT(*) FROM market_moderation_locks"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM market_moderation_operations"))
    }

    @Test
    fun `acquisition permit and moderation preparation are mutually exclusive`() {
        val policy = JdbcMarketModerationPolicy(dataSource, Clock.fixed(now, ZoneOffset.UTC))

        val whileAcquiring = policy.withAcquisitionPermit(ownerId) {
            store.prepare(request())
        }
        val afterRelease = store.prepare(request())

        assertEquals(MarketOperationResult.Status.CONFLICT, whileAcquiring.status())
        assertEquals(MarketOperationResult.Status.PREPARED, afterRelease.status())
    }

    @Test
    fun `prepared operation fences ordinary stall and shop repository writes`() {
        val staleStall = stallRepository.findById(StallId("stall-1"))!!
        val shops = ShopRepositorySql(dataSource)
        val staleShop = shops.findByStall("stall-1").single()
        store.prepare(request())

        assertFailsWith<MarketModerationConflictException> {
            stallRepository.save(staleStall.copy(winningBid = 9_999L))
        }
        assertFailsWith<MarketModerationConflictException> {
            shops.upsert(staleShop.copy(stockCount = 99))
        }
        assertEquals(10, shops.findById(staleShop.id)?.stockCount)
    }

    @Test
    fun `provider retries region access failures without claiming premature success`() {
        val regions = RecordingRegionAccess()
        val provider = MarketModerationProvider(
            store,
            DurableMarketMutationGate(dataSource),
            regions,
            Executors.newSingleThreadExecutor(),
        )
        try {
            val prepared = provider.prepare(request()).toCompletableFuture().join().operation().orElseThrow()
            regions.failClear = true
            assertFailsWith<CompletionException> {
                provider.confiscate(approval(prepared)).toCompletableFuture().join()
            }
            assertEquals("OWNED", stallValue("state"))
            assertEquals("PREPARED", store.findOperation(prepared.operationId()).orElseThrow().state().name)

            regions.failClear = false
            val held = provider.confiscate(approval(prepared)).toCompletableFuture().join().operation().orElseThrow()
            assertEquals("MODERATION_HOLD", stallValue("state"))

            regions.failRestore = true
            assertFailsWith<CompletionException> {
                provider.restore(
                    MarketRestoreRequest(held.operationId(), reviewerId, held.currentChecksum().orElseThrow()),
                ).toCompletableFuture().join()
            }
            assertEquals("RESTORED", store.findOperation(held.operationId()).orElseThrow().state().name)

            regions.failRestore = false
            val replayed = provider.restore(
                MarketRestoreRequest(held.operationId(), reviewerId, held.currentChecksum().orElseThrow()),
            ).toCompletableFuture().join()
            assertEquals(MarketOperationResult.Status.REPLAYED, replayed.status())
            assertTrue(regions.restoreCount >= 2)
        } finally {
            provider.close()
        }
    }

    private fun request(
        operationId: UUID = UUID.fromString("31cb0b96-992c-4678-b5d6-09d372f4ef12"),
        caseId: String = "CASE-100",
    ): MarketOperationRequest = MarketOperationRequest(
        operationId,
        ownerId,
        caseId,
        "stall-1",
        now.plusSeconds(7 * DAY_SECONDS),
        now.plusSeconds(30 * DAY_SECONDS),
        Optional.empty(),
    )

    private fun approval(operation: net.enthusia.market.api.moderation.MarketOperationRecord) =
        MarketConfiscationApproval(
            operation.operationId(),
            reviewerId,
            operation.snapshotChecksum(),
            now.plusSeconds(DAY_SECONDS),
        )

    private fun storeAt(instant: Instant): JdbcMarketModerationStore = JdbcMarketModerationStore(
        dataSource,
        Clock.fixed(instant, ZoneOffset.UTC),
    )

    private fun createOwnedStall() {
        stallRepository.create(
            Stall(
                id = StallId("stall-1"),
                regionId = "market-stall-1",
                world = "market",
                state = StallState.OWNED,
                owner = OwnerRef.solo(ownerId),
                ownerSince = now.minusSeconds(DAY_SECONDS),
                winningBid = 2_500L,
                rentTerms = RentTerms.formula(0.05),
                members = setOf(UUID.fromString("29cf4eb9-62ef-49bf-ae02-ab4e2146e099")),
                maxMembers = 4,
                nextRentAt = now.plusSeconds(DAY_SECONDS),
                kind = "bazaar",
                extraEntities = mapOf("armor_stand" to 2),
                extraTotal = 3,
            ),
        )
    }

    private fun createShop(signX: Int = 1) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO shop_items
                   (stall_id, owner, sign_world, sign_x, sign_y, sign_z,
                    container_world, container_x, container_y, container_z,
                    sell_item, sell_amount, cost_item, cost_amount, trusted,
                    hopper_allow_in, hopper_allow_out, frozen, admin_shop,
                    direction, search_enabled, sell_material, stock_count)
                   VALUES ('stall-1', ?, 'market', ?, 65, 1,
                           'market', ?, 64, 1, 'c2VsbA==', 1, 'Y29zdA==', 5, '',
                           1, 1, 0, 0, 'SELL', 1, 'DIAMOND', 10)""",
            ).use { statement ->
                statement.setString(1, ownerId.toString())
                statement.setInt(2, signX)
                statement.setInt(3, signX)
                statement.executeUpdate()
            }
        }
    }

    private fun stallValue(column: String): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT $column FROM stalls WHERE id = 'stall-1'").use { statement ->
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getString(1)
            }
        }
    }

    private fun shopFrozen(): Boolean = scalar("SELECT frozen FROM shop_items WHERE sign_x = 1") == 1

    private fun scalar(sql: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getInt(1)
            }
        }
    }

    private fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement -> statement.executeUpdate() }
        }
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
    }

    private class RecordingRegionAccess : MarketRegionAccessCoordinator {
        var failClear = false
        var failRestore = false
        var clearCount = 0
        var restoreCount = 0

        override fun clear(snapshot: MarketRegionAccessSnapshot) {
            clearCount++
            if (failClear) error("region clear failed")
        }

        override fun restore(snapshot: MarketRegionAccessSnapshot) {
            restoreCount++
            if (failRestore) error("region restore failed")
        }
    }
}
