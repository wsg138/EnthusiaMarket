package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketOperationResult
import net.enthusia.market.api.moderation.MarketRestoreRequest
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdbcMarketModerationProviderTest {
    private lateinit var fixture: MarketModerationStoreFixture

    @BeforeTest
    fun setUp() {
        fixture = MarketModerationStoreFixture().also { it.setUp() }
    }

    @AfterTest
    fun tearDown() = fixture.tearDown()

    @Test
    fun `tampered journal snapshot is quarantined before region access changes`() {
        val regions = RecordingRegionAccess()
        provider(regions).use { provider ->
            val prepared = provider.prepare(fixture.request()).toCompletableFuture().join().operation().orElseThrow()
            fixture.execute(
                "UPDATE market_moderation_operations SET snapshot_json = " +
                    "REPLACE(snapshot_json, 'market-stall-1', 'wrong-region')",
            )

            val result = provider.confiscate(fixture.approval(prepared)).toCompletableFuture().join()

            assertEquals(MarketOperationResult.Status.QUARANTINED, result.status())
            assertEquals(0, regions.clearCount)
            assertEquals("OWNED", fixture.stallValue("state"))
        }
    }

    @Test
    fun `provider retries failed region clear without claiming success`() {
        val regions = RecordingRegionAccess(failClear = true)
        provider(regions).use { provider ->
            val prepared = provider.prepare(fixture.request()).toCompletableFuture().join().operation().orElseThrow()

            assertFailsWith<CompletionException> {
                provider.confiscate(fixture.approval(prepared)).toCompletableFuture().join()
            }
            assertEquals("OWNED", fixture.stallValue("state"))
            assertEquals("PREPARED", fixture.store.findOperation(prepared.operationId()).orElseThrow().state().name)

            regions.failClear = false
            provider.confiscate(fixture.approval(prepared)).toCompletableFuture().join()
            assertEquals("MODERATION_HOLD", fixture.stallValue("state"))
        }
    }

    @Test
    fun `provider retries region restore after durable restore succeeds`() {
        val regions = RecordingRegionAccess()
        provider(regions).use { provider ->
            val prepared = provider.prepare(fixture.request()).toCompletableFuture().join().operation().orElseThrow()
            val held = provider.confiscate(fixture.approval(prepared)).toCompletableFuture().join().operation().orElseThrow()
            regions.failRestore = true

            assertFailsWith<CompletionException> { restore(provider, held.currentChecksum().orElseThrow()) }
            assertEquals("RESTORED", fixture.store.findOperation(held.operationId()).orElseThrow().state().name)
            assertFalse(DurableMarketMutationGate(fixture.dataSource).isStallLocked("stall-1"))

            regions.failRestore = false
            val replayed = restore(provider, held.currentChecksum().orElseThrow())
            assertEquals(MarketOperationResult.Status.REPLAYED, replayed.status())
            assertTrue(regions.restoreCount >= 2)
        }
    }

    @Test
    fun `provider reports executor rejection through the returned stage`() {
        val executor = Executors.newSingleThreadExecutor().also(ExecutorService::shutdown)
        provider(RecordingRegionAccess(), executor).use { provider ->
            val failure = assertFailsWith<CompletionException> {
                provider.findStalls(fixture.ownerId).toCompletableFuture().join()
            }
            assertTrue(failure.cause is IllegalStateException)
        }
    }

    private fun provider(
        regions: RecordingRegionAccess,
        executor: ExecutorService = Executors.newSingleThreadExecutor(),
    ): MarketModerationProvider = MarketModerationProvider(
        fixture.store,
        DurableMarketMutationGate(fixture.dataSource),
        regions,
        executor,
    )

    private fun restore(provider: MarketModerationProvider, checksum: String): MarketOperationResult =
        provider.restore(
            MarketRestoreRequest(fixture.request().operationId(), fixture.reviewerId, checksum),
        ).toCompletableFuture().join()
}

private class RecordingRegionAccess(
    var failClear: Boolean = false,
    var failRestore: Boolean = false,
) : MarketRegionAccessCoordinator {
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
