package net.badgersmc.em.infrastructure.moderation

import net.badgersmc.em.infrastructure.persistence.MarketModerationConflictException
import net.badgersmc.em.infrastructure.persistence.ShopRepositorySql
import net.enthusia.market.api.moderation.MarketBlacklistRemoval
import net.enthusia.market.api.moderation.MarketBlacklistRequest
import net.enthusia.market.api.moderation.MarketBlacklistResult
import net.enthusia.market.api.moderation.MarketConfiscationApproval
import net.enthusia.market.api.moderation.MarketOperationResult
import net.enthusia.market.api.moderation.MarketRestoreRequest
import java.time.Clock
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
import kotlin.test.assertTrue

class JdbcMarketModerationStoreTest {
    private lateinit var fixture: MarketModerationStoreFixture

    @BeforeTest
    fun setUp() {
        fixture = MarketModerationStoreFixture()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `prepare is durable idempotent and never removes ownership on elapsed time`() {
        val request = fixture.request()

        val prepared = fixture.store.prepare(request)
        val replayedAfterRestart = fixture.storeAt(fixture.now.plusSeconds(8 * DAY_SECONDS)).prepare(request)

        assertEquals(MarketOperationResult.Status.PREPARED, prepared.status())
        assertEquals(MarketOperationResult.Status.REPLAYED, replayedAfterRestart.status())
        assertEquals(fixture.ownerId.toString(), fixture.stallValue("owner_id"))
        assertEquals("OWNED", fixture.stallValue("state"))
        assertTrue(fixture.shopFrozen())
        assertFalse(fixture.store.canAcquire(fixture.ownerId))
        assertEquals(
            prepared.operation().orElseThrow(),
            fixture.store.findOperation(request.operationId()).orElseThrow(),
        )
    }

    @Test
    fun `prepare replay requires the complete original request`() {
        val original = fixture.request(
            blacklistExpiresAt = Optional.of(fixture.now.plusSeconds(14 * DAY_SECONDS)),
        )
        assertEquals(MarketOperationResult.Status.PREPARED, fixture.store.prepare(original).status())
        assertEquals(MarketOperationResult.Status.REPLAYED, fixture.store.prepare(original).status())

        val mismatches = listOf(
            fixture.request(
                reviewDueAt = fixture.now.plusSeconds(8 * DAY_SECONDS),
                blacklistExpiresAt = original.blacklistExpiresAt(),
            ),
            fixture.request(
                recoveryUntil = fixture.now.plusSeconds(31 * DAY_SECONDS),
                blacklistExpiresAt = original.blacklistExpiresAt(),
            ),
            fixture.request(blacklistExpiresAt = Optional.of(fixture.now.plusSeconds(15 * DAY_SECONDS))),
            fixture.request(blacklistExpiresAt = Optional.empty()),
        )

        mismatches.forEach { mismatch ->
            assertEquals(MarketOperationResult.Status.CONFLICT, fixture.store.prepare(mismatch).status())
        }
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM market_moderation_operations"))
    }

    @Test
    fun `reviewed hold and restore preserve exact ownership and shop state`() {
        val prepared = fixture.store.prepare(fixture.request()).operation().orElseThrow()
        val heldResult = fixture.store.confiscate(fixture.approval(prepared))
        val held = heldResult.operation().orElseThrow()

        assertEquals(MarketOperationResult.Status.HELD, heldResult.status())
        fixture.assertHeldState()

        val restored = fixture.store.restore(
            MarketRestoreRequest(held.operationId(), fixture.reviewerId, held.currentChecksum().orElseThrow()),
        )

        assertEquals(MarketOperationResult.Status.RESTORED, restored.status())
        fixture.assertRestoredState()
    }

    @Test
    fun `release reverses preparation without entering a moderation hold`() {
        val prepared = fixture.store.prepare(fixture.request()).operation().orElseThrow()

        val released = fixture.store.release(prepared.operationId(), prepared.snapshotChecksum())

        assertEquals(MarketOperationResult.Status.RELEASED, released.status())
        assertEquals("OWNED", fixture.stallValue("state"))
        assertFalse(fixture.shopFrozen())
        assertTrue(fixture.store.canAcquire(fixture.ownerId))
    }

    @Test
    fun `release maps a missing reservation to conflict and rolls back restoration`() {
        val prepared = fixture.store.prepare(fixture.request()).operation().orElseThrow()
        fixture.execute("DELETE FROM market_moderation_locks WHERE stall_id = 'stall-1'")

        val result = fixture.store.release(prepared.operationId(), prepared.snapshotChecksum())

        assertEquals(MarketOperationResult.Status.CONFLICT, result.status())
        assertEquals("PREPARED", fixture.store.findOperation(prepared.operationId()).orElseThrow().state().name)
        assertTrue(fixture.shopFrozen())
    }

    @Test
    fun `stale restore checksum leaves the reviewed hold intact`() {
        val prepared = fixture.store.prepare(fixture.request()).operation().orElseThrow()
        val held = fixture.store.confiscate(fixture.approval(prepared)).operation().orElseThrow()

        val stale = fixture.store.restore(
            MarketRestoreRequest(held.operationId(), fixture.reviewerId, "0".repeat(64)),
        )

        assertEquals(MarketOperationResult.Status.CONFLICT, stale.status())
        assertEquals("MODERATION_HOLD", fixture.stallValue("state"))
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM market_moderation_locks"))
    }

    @Test
    fun `second operation cannot reserve an already prepared stall`() {
        val first = fixture.store.prepare(fixture.request())
        val second = fixture.store.prepare(
            fixture.request(operationId = UUID.randomUUID(), caseId = "CASE-OTHER"),
        )

        assertEquals(MarketOperationResult.Status.PREPARED, first.status())
        assertEquals(MarketOperationResult.Status.CONFLICT, second.status())
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM market_moderation_operations"))
    }

    @Test
    fun `tampered prepared state is quarantined instead of confiscated`() {
        val prepared = fixture.store.prepare(fixture.request()).operation().orElseThrow()
        fixture.execute("UPDATE shop_items SET frozen = 0 WHERE stall_id = 'stall-1'")

        val result = fixture.store.confiscate(
            MarketConfiscationApproval(
                prepared.operationId(),
                fixture.reviewerId,
                prepared.snapshotChecksum(),
                fixture.now.plusSeconds(DAY_SECONDS),
            ),
        )

        assertEquals(MarketOperationResult.Status.QUARANTINED, result.status())
        assertEquals("OWNED", fixture.stallValue("state"))
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM market_moderation_locks"))
    }

    @Test
    fun `tampered journal snapshot is quarantined before region access changes`() {
        val regions = RecordingRegionAccess()
        val provider = MarketModerationProvider(
            fixture.store,
            DurableMarketMutationGate(fixture.dataSource),
            regions,
            Executors.newSingleThreadExecutor(),
        )
        try {
            val prepared = provider.prepare(fixture.request()).toCompletableFuture().join().operation().orElseThrow()
            fixture.execute(
                "UPDATE market_moderation_operations SET snapshot_json = " +
                    "REPLACE(snapshot_json, 'market-stall-1', 'wrong-region')",
            )

            val result = provider.confiscate(fixture.approval(prepared)).toCompletableFuture().join()

            assertEquals(MarketOperationResult.Status.QUARANTINED, result.status())
            assertEquals(0, regions.clearCount)
            assertEquals("OWNED", fixture.stallValue("state"))
        } finally {
            provider.close()
        }
    }

    @Test
    fun `standalone blacklist uses revision checks and idempotent removal`() {
        val applyId = UUID.randomUUID()
        val applied = fixture.store.applyBlacklist(
            MarketBlacklistRequest(applyId, fixture.ownerId, "CASE-BLACKLIST", Optional.empty()),
        )
        val state = applied.blacklist().orElseThrow()
        val removalId = UUID.randomUUID()
        val removal = MarketBlacklistRemoval(
            removalId,
            fixture.ownerId,
            "CASE-BLACKLIST",
            state.revision(),
        )

        val removed = fixture.store.removeBlacklist(removal)
        val replayed = fixture.store.removeBlacklist(removal)

        assertEquals(MarketBlacklistResult.Status.APPLIED, applied.status())
        assertEquals(MarketBlacklistResult.Status.REMOVED, removed.status())
        assertEquals(MarketBlacklistResult.Status.REPLAYED, replayed.status())
        assertTrue(fixture.store.canAcquire(fixture.ownerId))
    }

    @Test
    fun `wrong blacklist revision preserves the active restriction`() {
        val applied = fixture.store.applyBlacklist(
            MarketBlacklistRequest(UUID.randomUUID(), fixture.ownerId, "CASE-BLACKLIST", Optional.empty()),
        ).blacklist().orElseThrow()

        val result = fixture.store.removeBlacklist(
            MarketBlacklistRemoval(
                UUID.randomUUID(),
                fixture.ownerId,
                "CASE-BLACKLIST",
                applied.revision() + 1L,
            ),
        )

        assertEquals(MarketBlacklistResult.Status.CONFLICT, result.status())
        assertFalse(fixture.store.canAcquire(fixture.ownerId))
    }

    @Test
    fun `snapshot safety limit rejects stalls with more than one hundred shops`() {
        repeat(100) { fixture.createShop(signX = it + 2) }

        val result = fixture.store.prepare(fixture.request())

        assertEquals(MarketOperationResult.Status.REJECTED, result.status())
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM market_moderation_locks"))
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM market_moderation_operations"))
    }

    @Test
    fun `stall lookup rejects an oversized result`() {
        val original = fixture.stallRepository.findById(net.badgersmc.em.domain.stall.StallId("stall-1"))!!
        repeat(100) { index ->
            fixture.stallRepository.create(
                original.copy(
                    id = net.badgersmc.em.domain.stall.StallId("extra-stall-$index"),
                    regionId = "market-extra-stall-$index",
                ),
            )
        }

        assertFailsWith<MarketModerationRejected> {
            fixture.store.findStalls(fixture.ownerId)
        }
    }

    @Test
    fun `acquisition permit and moderation preparation are mutually exclusive`() {
        val policy = JdbcMarketModerationPolicy(
            fixture.dataSource,
            Clock.fixed(fixture.now, ZoneOffset.UTC),
        )

        val whileAcquiring = policy.withAcquisitionPermit(fixture.ownerId) {
            fixture.store.prepare(fixture.request())
        }
        val afterRelease = fixture.store.prepare(fixture.request())

        assertEquals(MarketOperationResult.Status.CONFLICT, whileAcquiring.status())
        assertEquals(MarketOperationResult.Status.PREPARED, afterRelease.status())
    }

    @Test
    fun `prepared operation fences ordinary stall and shop repository writes`() {
        val stallId = net.badgersmc.em.domain.stall.StallId("stall-1")
        val staleStall = fixture.stallRepository.findById(stallId)!!
        val shops = ShopRepositorySql(fixture.dataSource)
        val staleShop = shops.findByStall("stall-1").single()
        fixture.store.prepare(fixture.request())

        assertFailsWith<MarketModerationConflictException> {
            fixture.stallRepository.save(staleStall.copy(winningBid = 9_999L))
        }
        assertFailsWith<MarketModerationConflictException> {
            shops.upsert(staleShop.copy(stockCount = 99))
        }
        assertFailsWith<MarketModerationConflictException> {
            shops.delete(staleShop.id)
        }
        assertEquals(10, shops.findById(staleShop.id)?.stockCount)
    }

    @Test
    fun `stock batch skips a moderated shop without discarding unlocked updates`() {
        val stallId = net.badgersmc.em.domain.stall.StallId("stall-1")
        val original = fixture.stallRepository.findById(stallId)!!
        fixture.stallRepository.create(
            original.copy(
                id = net.badgersmc.em.domain.stall.StallId("stall-2"),
                regionId = "market-stall-2",
            ),
        )
        fixture.createShop(signX = 2, stallId = "stall-2")
        val shops = ShopRepositorySql(fixture.dataSource)
        val locked = shops.findByStall("stall-1").single()
        val unlocked = shops.findByStall("stall-2").single()
        fixture.store.prepare(fixture.request())

        shops.updateStockBatch(mapOf(locked.id to 99, unlocked.id to 20))

        assertEquals(10, shops.findById(locked.id)?.stockCount)
        assertEquals(20, shops.findById(unlocked.id)?.stockCount)
    }

    @Test
    fun `provider retries region access failures without claiming premature success`() {
        val regions = RecordingRegionAccess()
        val gate = DurableMarketMutationGate(fixture.dataSource)
        val provider = MarketModerationProvider(
            fixture.store,
            gate,
            regions,
            Executors.newSingleThreadExecutor(),
        )
        try {
            val prepared = provider.prepare(fixture.request()).toCompletableFuture().join().operation().orElseThrow()
            regions.failClear = true
            assertFailsWith<CompletionException> {
                provider.confiscate(fixture.approval(prepared)).toCompletableFuture().join()
            }
            assertEquals("OWNED", fixture.stallValue("state"))
            assertEquals("PREPARED", fixture.store.findOperation(prepared.operationId()).orElseThrow().state().name)

            regions.failClear = false
            val held = provider.confiscate(fixture.approval(prepared)).toCompletableFuture().join().operation().orElseThrow()
            assertEquals("MODERATION_HOLD", fixture.stallValue("state"))

            regions.failRestore = true
            assertFailsWith<CompletionException> {
                provider.restore(
                    MarketRestoreRequest(
                        held.operationId(),
                        fixture.reviewerId,
                        held.currentChecksum().orElseThrow(),
                    ),
                ).toCompletableFuture().join()
            }
            assertEquals("RESTORED", fixture.store.findOperation(held.operationId()).orElseThrow().state().name)
            assertFalse(gate.isStallLocked("stall-1"))

            regions.failRestore = false
            val replayed = provider.restore(
                MarketRestoreRequest(
                    held.operationId(),
                    fixture.reviewerId,
                    held.currentChecksum().orElseThrow(),
                ),
            ).toCompletableFuture().join()
            assertEquals(MarketOperationResult.Status.REPLAYED, replayed.status())
            assertTrue(regions.restoreCount >= 2)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `provider reports executor rejection through the returned stage`() {
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdown()
        val provider = MarketModerationProvider(
            fixture.store,
            DurableMarketMutationGate(fixture.dataSource),
            RecordingRegionAccess(),
            executor,
        )
        try {
            val failure = assertFailsWith<CompletionException> {
                provider.findStalls(fixture.ownerId).toCompletableFuture().join()
            }

            assertTrue(failure.cause is IllegalStateException)
        } finally {
            provider.close()
        }
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
    }
}
