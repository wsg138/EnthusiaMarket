package net.badgersmc.em.infrastructure.moderation

import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.infrastructure.persistence.MarketModerationConflictException
import net.badgersmc.em.infrastructure.persistence.ShopRepositorySql
import net.enthusia.market.api.moderation.MarketOperationRequest
import net.enthusia.market.api.moderation.MarketOperationResult
import net.enthusia.market.api.moderation.MarketRestoreRequest
import java.util.Optional
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcMarketModerationStoreTest {
    private lateinit var fixture: MarketModerationStoreFixture

    @BeforeTest
    fun setUp() {
        fixture = MarketModerationStoreFixture().also { it.setUp() }
    }

    @AfterTest
    fun tearDown() = fixture.tearDown()

    @Test
    fun `prepare is durable idempotent and never removes ownership on elapsed time`() {
        val request = fixture.request()
        val prepared = fixture.store.prepare(request)
        val replayed = fixture.storeAt(
            fixture.now.plusSeconds(8 * MarketModerationStoreFixture.DAY_SECONDS),
        ).prepare(request)

        assertEquals(MarketOperationResult.Status.PREPARED, prepared.status())
        assertEquals(MarketOperationResult.Status.REPLAYED, replayed.status())
        assertEquals(fixture.ownerId.toString(), fixture.stallValue("owner_id"))
        assertEquals("OWNED", fixture.stallValue("state"))
        assertTrue(fixture.shopFrozen())
        assertFalse(fixture.store.canAcquire(fixture.ownerId))
        assertEquals(prepared.operation().orElseThrow(), fixture.store.findOperation(request.operationId()).orElseThrow())
    }

    @Test
    fun `prepare replay requires the complete original request`() {
        val original = fixture.request(
            blacklistExpiresAt = Optional.of(
                fixture.now.plusSeconds(14 * MarketModerationStoreFixture.DAY_SECONDS),
            ),
        )
        assertEquals(MarketOperationResult.Status.PREPARED, fixture.store.prepare(original).status())
        assertEquals(MarketOperationResult.Status.REPLAYED, fixture.store.prepare(original).status())

        replayMismatches(original).forEach { mismatch ->
            assertEquals(MarketOperationResult.Status.CONFLICT, fixture.store.prepare(mismatch).status())
        }
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM market_moderation_operations"))
    }

    @Test
    fun `reviewed hold removes ownership and freezes shop`() {
        val prepared = fixture.store.prepare(fixture.request()).operation().orElseThrow()
        val held = fixture.store.confiscate(fixture.approval(prepared))

        assertEquals(MarketOperationResult.Status.HELD, held.status())
        assertEquals("MODERATION_HOLD", fixture.stallValue("state"))
        assertEquals("NONE", fixture.stallValue("owner_type"))
        assertEquals("", fixture.stallValue("owner_id"))
        assertTrue(fixture.shopFrozen())
    }

    @Test
    fun `restore preserves exact ownership and shop state`() {
        val prepared = fixture.store.prepare(fixture.request()).operation().orElseThrow()
        val held = fixture.store.confiscate(fixture.approval(prepared)).operation().orElseThrow()
        val restored = fixture.store.restore(
            MarketRestoreRequest(held.operationId(), fixture.reviewerId, held.currentChecksum().orElseThrow()),
        )

        assertEquals(MarketOperationResult.Status.RESTORED, restored.status())
        assertEquals("OWNED", fixture.stallValue("state"))
        assertEquals("SOLO", fixture.stallValue("owner_type"))
        assertEquals(fixture.ownerId.toString(), fixture.stallValue("owner_id"))
        assertFalse(fixture.shopFrozen())
        assertNull(fixture.store.getBlacklist(fixture.ownerId).orElse(null))
        assertTrue(fixture.store.canAcquire(fixture.ownerId))
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM market_moderation_locks"))
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

        val result = fixture.store.confiscate(fixture.approval(prepared))

        assertEquals(MarketOperationResult.Status.QUARANTINED, result.status())
        assertEquals("OWNED", fixture.stallValue("state"))
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM market_moderation_locks"))
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
        val original = fixture.stallRepository.findById(StallId("stall-1"))!!
        repeat(100) { index ->
            fixture.stallRepository.create(
                original.copy(
                    id = StallId("extra-stall-$index"),
                    regionId = "market-extra-stall-$index",
                ),
            )
        }

        assertFailsWith<MarketModerationRejected> {
            fixture.store.findStalls(fixture.ownerId)
        }
    }

    @Test
    fun `prepared operation fences ordinary stall and shop repository writes`() {
        val staleStall = fixture.stallRepository.findById(StallId("stall-1"))!!
        val shops = ShopRepositorySql(fixture.dataSource)
        val staleShop = shops.findByStall("stall-1").single()
        fixture.store.prepare(fixture.request())

        assertFailsWith<MarketModerationConflictException> {
            fixture.stallRepository.save(staleStall.copy(winningBid = 9_999L))
        }
        assertFailsWith<MarketModerationConflictException> {
            shops.upsert(staleShop.copy(stockCount = 99))
        }
        assertFailsWith<MarketModerationConflictException> { shops.delete(staleShop.id) }
        assertEquals(10, shops.findById(staleShop.id)?.stockCount)
    }

    @Test
    fun `stock batch skips a moderated shop without discarding unlocked updates`() {
        val original = fixture.stallRepository.findById(StallId("stall-1"))!!
        fixture.stallRepository.create(original.copy(id = StallId("stall-2"), regionId = "market-stall-2"))
        fixture.createShop(signX = 2, stallId = "stall-2")
        val shops = ShopRepositorySql(fixture.dataSource)
        val locked = shops.findByStall("stall-1").single()
        val unlocked = shops.findByStall("stall-2").single()
        fixture.store.prepare(fixture.request())

        shops.updateStockBatch(mapOf(locked.id to 99, unlocked.id to 20))

        assertEquals(10, shops.findById(locked.id)?.stockCount)
        assertEquals(20, shops.findById(unlocked.id)?.stockCount)
    }

    private fun replayMismatches(original: MarketOperationRequest): List<MarketOperationRequest> = listOf(
        fixture.request(
            reviewDueAt = fixture.now.plusSeconds(8 * MarketModerationStoreFixture.DAY_SECONDS),
            blacklistExpiresAt = original.blacklistExpiresAt(),
        ),
        fixture.request(
            recoveryUntil = fixture.now.plusSeconds(31 * MarketModerationStoreFixture.DAY_SECONDS),
            blacklistExpiresAt = original.blacklistExpiresAt(),
        ),
        fixture.request(
            blacklistExpiresAt = Optional.of(
                fixture.now.plusSeconds(15 * MarketModerationStoreFixture.DAY_SECONDS),
            ),
        ),
        fixture.request(blacklistExpiresAt = Optional.empty()),
    )
}
