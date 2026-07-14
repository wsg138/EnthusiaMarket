package net.badgersmc.em.websync

import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WebsiteSyncOutboxTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `enable-time full reconciliation supersedes stale stalls and delivers full first`() {
        val outbox = outbox()
        outbox.enqueueStall(stall("stall1"))
        val full = outbox.enqueueFull((1..71).map { stall("stall$it") })
        val newer = outbox.enqueueStall(stall("stall1", x = 2))

        assertEquals(DeliveryKind.FULL, outbox.nextReady()!!.kind)
        assertTrue(outbox.acknowledge(full))
        assertEquals(newer.eventId, outbox.nextReady()!!.eventId)
    }

    @Test
    fun `old acknowledgement cannot delete newer stall state`() {
        val outbox = outbox()
        val old = outbox.enqueueStall(stall("stall1"))
        val current = outbox.enqueueStall(stall("stall1", x = 3))
        assertFalse(outbox.acknowledge(old))
        assertEquals(current.eventId, outbox.nextReady()!!.eventId)
    }

    @Test
    fun `server epoch persists across outbox instances`() {
        val dataSource = dataSource()
        WebsiteSyncMigrationRunner(dataSource).runAll()
        val first = WebsiteSyncOutbox(dataSource).serverEpoch()
        assertEquals(first, WebsiteSyncOutbox(dataSource).serverEpoch())
    }

    @Test
    fun `retry persists its deadline and attempt count and retryNow makes it ready`() {
        val outbox = outbox()
        val delivery = outbox.enqueueStall(stall("stall1"))
        val retryAt = System.currentTimeMillis() + 60_000
        outbox.retry(delivery, retryAt)
        assertNull(outbox.nextReady())
        assertEquals(retryAt, outbox.nextAttemptAt())
        outbox.retryNow()
        assertEquals(1, outbox.nextReady()!!.attemptCount)
    }

    @Test
    fun `future full retry blocks ready stalls and remains the earliest wake`() {
        val outbox = outbox()
        val full = outbox.enqueueFull((1..71).map { stall("stall$it") })
        val retryAt = System.currentTimeMillis() + 60_000
        outbox.retry(full, retryAt)
        outbox.enqueueStall(stall("stall1", 4))
        assertNull(outbox.nextReady())
        assertEquals(retryAt, outbox.nextAttemptAt())
    }

    @Test
    fun `status and pending state survive a new outbox instance`() {
        val dataSource = dataSource()
        WebsiteSyncMigrationRunner(dataSource).runAll()
        val first = WebsiteSyncOutbox(dataSource)
        first.enqueueStall(stall("stall1"))
        val status = WebsiteSyncOutbox(dataSource).status()
        assertEquals(1, status.pendingStalls)
        assertFalse(status.pendingFull)
        assertTrue(status.oldestPendingAt != null)
        assertEquals(DeliveryKind.STALL, WebsiteSyncOutbox(dataSource).nextReady()!!.kind)
    }

    @Test
    fun `payload limits reject oversized stall and full bodies without partial writes`() {
        val outbox = outbox()
        val oversized = stall("stall1").copy(owner = unowned("x".repeat(WebsiteSyncOutbox.STALL_BODY_LIMIT)))
        assertFailsWith<IllegalArgumentException> { outbox.enqueueStall(oversized) }
        assertEquals(0, outbox.status().pendingStalls)

        val largeStalls = (1..71).map { stall("stall$it").copy(owner = unowned("x".repeat(60_000))) }
        assertFailsWith<IllegalArgumentException> { outbox.enqueueFull(largeStalls) }
        assertFalse(outbox.status().pendingFull)
    }

    private fun outbox(): WebsiteSyncOutbox = dataSource().also { WebsiteSyncMigrationRunner(it).runAll() }
        .let(::WebsiteSyncOutbox)

    private fun dataSource() = SQLiteDataSource().apply { url = "jdbc:sqlite:${directory.resolve("outbox.db")}" }

    private fun stall(id: String, x: Int = 0) = PublicStall(
        id, "building-1", 1, PublicLocation("world", x, 64, 0),
        PublicOwner("NONE", null, null, "Unowned", avatar = PublicAvatar("NONE")),
        null, null, emptyList(), emptyList(),
    )

    private fun unowned(name: String) = PublicOwner("NONE", null, null, name, null, PublicAvatar("NONE"))
}
