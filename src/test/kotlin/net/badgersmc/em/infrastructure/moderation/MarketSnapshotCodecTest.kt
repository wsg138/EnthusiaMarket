package net.badgersmc.em.infrastructure.moderation

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MarketSnapshotCodecTest {
    private val codec = MarketSnapshotCodec()

    @Test
    fun `decode rejects snapshots without required root objects`() {
        listOf(
            "{}",
            "{\"shops\":[]}",
            "{\"stall\":{}}",
            "{\"stall\":{},\"shops\":[null]}",
            "{\"stall\":{},\"shops\":[],\"blacklist\":[]}",
        ).forEach { json ->
            assertFailsWith<MarketModerationConflict> { codec.decode(json) }
        }
    }
}
