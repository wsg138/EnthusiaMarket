package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketBlacklistRemoval
import net.enthusia.market.api.moderation.MarketBlacklistRequest
import net.enthusia.market.api.moderation.MarketBlacklistResult
import net.enthusia.market.api.moderation.MarketOperationResult
import java.time.Clock
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdbcMarketRestrictionTest {
    private lateinit var fixture: MarketModerationStoreFixture

    @BeforeTest
    fun setUp() {
        fixture = MarketModerationStoreFixture().also { it.setUp() }
    }

    @AfterTest
    fun tearDown() = fixture.tearDown()

    @Test
    fun `standalone blacklist uses revision checks and idempotent removal`() {
        val applied = fixture.store.applyBlacklist(
            MarketBlacklistRequest(UUID.randomUUID(), fixture.ownerId, "CASE-BLACKLIST", Optional.empty()),
        )
        val state = applied.blacklist().orElseThrow()
        val removal = MarketBlacklistRemoval(
            UUID.randomUUID(),
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
}
