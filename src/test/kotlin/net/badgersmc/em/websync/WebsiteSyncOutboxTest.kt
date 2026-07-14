package net.badgersmc.em.websync

import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private fun outbox(): WebsiteSyncOutbox = dataSource().also { WebsiteSyncMigrationRunner(it).runAll() }
        .let(::WebsiteSyncOutbox)

    private fun dataSource() = SQLiteDataSource().apply { url = "jdbc:sqlite:${directory.resolve("outbox.db")}" }

    private fun stall(id: String, x: Int = 0) = PublicStall(
        id, "building-1", 1, PublicLocation("world", x, 64, 0),
        PublicOwner("NONE", null, null, "Unowned", avatar = PublicAvatar("NONE")),
        null, null, emptyList(), emptyList(),
    )
}
