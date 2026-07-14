package net.badgersmc.em.websync

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

enum class DeliveryKind { FULL, STALL }
data class PendingDelivery(
    val kind: DeliveryKind,
    val stallId: String?,
    val revision: Long,
    val eventId: String,
    val body: ByteArray,
    val hash: String,
    val attemptCount: Int,
)
data class OutboxStatus(
    val pendingStalls: Int,
    val pendingFull: Boolean,
    val oldestPendingAt: Long?,
    val snapshotRevision: Long,
    val lastFullSuccess: Long?,
    val lastStallSuccess: Long?,
)
data class IncludedRevision(val stallId: String, val revision: Long, val hash: String)

class WebsiteSyncOutbox(private val dataSource: DataSource) {
    companion object {
        const val STALL_BODY_LIMIT = 256 * 1024
        const val FULL_BODY_LIMIT = 4 * 1024 * 1024
    }

    fun serverEpoch(): String = transaction { connection ->
        state(connection, "server_epoch") ?: UUID.randomUUID().toString().also {
            setState(connection, "server_epoch", it)
        }
    }

    fun enqueueStall(stall: PublicStall): PendingDelivery = transaction { connection ->
        val epoch = state(connection, "server_epoch") ?: UUID.randomUUID().toString().also {
            setState(connection, "server_epoch", it)
        }
        val prior = stallRow(connection, stall.id)
        val revision = (prior?.revision ?: 0L) + 1L
        val eventId = UUID.randomUUID().toString()
        val body = WebsiteSyncJson.bytes(
            StallUpdateRequest(serverEpoch = epoch, eventId = eventId, sentAt = Instant.now().toString(),
                revision = revision, stall = stall)
        )
        require(body.size <= STALL_BODY_LIMIT) { "stall_body_limit" }
        val hash = MarketRequestSigner.bodyHash(body)
        val write = StallWrite(stall.id, revision, hash, body, eventId)
        if (prior == null) insertStall(connection, write)
        else updatePendingStall(connection, write)
        PendingDelivery(DeliveryKind.STALL, stall.id, revision, eventId, body, hash, 0)
    }

    fun enqueueFull(stalls: List<PublicStall>): PendingDelivery = transaction { connection ->
        require(stalls.isNotEmpty()) { "full_stall_count" }
        val epoch = state(connection, "server_epoch") ?: UUID.randomUUID().toString().also {
            setState(connection, "server_epoch", it)
        }
        val snapshotRevision = (state(connection, "snapshot_revision")?.toLongOrNull() ?: 0L) + 1L
        setState(connection, "snapshot_revision", snapshotRevision.toString())
        val included = stalls.sortedByNaturalId().map { stall ->
            val prior = stallRow(connection, stall.id)
            val revision = (prior?.revision ?: 0L) + 1L
            revision to stall
        }
        val eventId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val body = WebsiteSyncJson.bytes(
            FullSyncRequest(
                serverEpoch = epoch,
                eventId = eventId,
                sentAt = now,
                snapshotRevision = snapshotRevision,
                generatedAt = now,
                stalls = included.map { RevisionedStall(it.first, it.second) },
            )
        )
        require(body.size <= FULL_BODY_LIMIT) { "full_body_limit" }
        val includedState = included.map { (revision, stall) ->
            val stallBytes = WebsiteSyncJson.bytes(stall)
            IncludedRevision(stall.id, revision, MarketRequestSigner.bodyHash(stallBytes))
        }
        setState(connection, "snapshot_hash", MarketRequestSigner.bodyHash(body))
        for (entry in includedState) {
            val prior = stallRow(connection, entry.stallId)
            if (prior == null) insertStall(connection, StallWrite(entry.stallId, entry.revision, entry.hash, null, null))
            else updateFullIncluded(connection, entry)
        }
        connection.prepareStatement("DELETE FROM em_websync_full").use { it.executeUpdate() }
        connection.prepareStatement(
            "INSERT INTO em_websync_full (singleton_id, snapshot_revision, pending_body, pending_event_id, " +
                "included_state, pending_since, retry_at, attempt_count, success_at) VALUES (1, ?, ?, ?, ?, ?, ?, 0, NULL)"
        ).use {
            it.setLong(1, snapshotRevision); it.setBytes(2, body); it.setString(3, eventId)
            val nowMillis = System.currentTimeMillis()
            it.setBytes(4, WebsiteSyncJson.bytes(includedState)); it.setLong(5, nowMillis)
            it.setLong(6, nowMillis); it.executeUpdate()
        }
        PendingDelivery(DeliveryKind.FULL, null, snapshotRevision, eventId, body,
            MarketRequestSigner.bodyHash(body), 0)
    }

    fun nextReady(now: Long = System.currentTimeMillis()): PendingDelivery? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT snapshot_revision, pending_body, pending_event_id, attempt_count FROM em_websync_full " +
                "WHERE singleton_id = 1 AND (retry_at IS NULL OR retry_at <= ?)"
        ).use { statement ->
            statement.setLong(1, now)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    val body = result.getBytes("pending_body")
                    return PendingDelivery(DeliveryKind.FULL, null, result.getLong("snapshot_revision"),
                        result.getString("pending_event_id"), body, MarketRequestSigner.bodyHash(body),
                        result.getInt("attempt_count"))
                }
            }
        }
        if (hasPendingFull(connection)) return null
        connection.prepareStatement(
            "SELECT stall_id, revision, pending_body, pending_event_id, latest_hash, attempt_count " +
                "FROM em_websync_stalls WHERE pending_body IS NOT NULL AND (retry_at IS NULL OR retry_at <= ?) " +
                "ORDER BY retry_at, stall_id"
        ).use { statement ->
            statement.setLong(1, now)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                PendingDelivery(DeliveryKind.STALL, result.getString("stall_id"), result.getLong("revision"),
                    result.getString("pending_event_id"), result.getBytes("pending_body"),
                    result.getString("latest_hash"), result.getInt("attempt_count"))
            }
        }
    }

    /** Earliest time at which [nextReady] can return work, preserving full-sync priority. */
    @Suppress("NestedBlockDepth")
    fun nextAttemptAt(): Long? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT retry_at FROM em_websync_full WHERE singleton_id = 1").use { statement ->
            statement.executeQuery().use { result ->
                if (result.next()) {
                    val retryAt = result.getLong(1)
                    return if (result.wasNull()) 0L else retryAt
                }
            }
        }
        nullableLong(connection, "SELECT MIN(retry_at) FROM em_websync_stalls WHERE pending_body IS NOT NULL")
    }

    fun acknowledge(delivery: PendingDelivery, at: Long = System.currentTimeMillis()): Boolean = transaction { connection ->
        when (delivery.kind) {
            DeliveryKind.STALL -> connection.prepareStatement(
                "UPDATE em_websync_stalls SET pending_body = NULL, pending_event_id = NULL, pending_since = NULL, retry_at = NULL, " +
                    "attempt_count = 0, acknowledged_revision = ?, acknowledged_hash = ?, success_at = ? " +
                    "WHERE stall_id = ? AND revision = ? AND pending_event_id = ? AND latest_hash = ?"
            ).use {
                it.setLong(1, delivery.revision); it.setString(2, delivery.hash); it.setLong(3, at)
                it.setString(4, delivery.stallId); it.setLong(5, delivery.revision)
                it.setString(6, delivery.eventId); it.setString(7, delivery.hash); it.executeUpdate() == 1
            }
            DeliveryKind.FULL -> {
                val included = connection.prepareStatement(
                    "SELECT included_state FROM em_websync_full WHERE singleton_id = 1 AND snapshot_revision = ? AND pending_event_id = ?"
                ).use { statement ->
                    statement.setLong(1, delivery.revision); statement.setString(2, delivery.eventId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) emptyList() else {
                            WebsiteSyncJson.fromJsonIncludedRevisions(result.getBytes(1))
                        }
                    }
                }
                val removed = connection.prepareStatement(
                    "DELETE FROM em_websync_full WHERE singleton_id = 1 AND snapshot_revision = ? AND pending_event_id = ?"
                ).use { it.setLong(1, delivery.revision); it.setString(2, delivery.eventId); it.executeUpdate() == 1 }
                if (removed) {
                    included.forEach { entry ->
                        connection.prepareStatement(
                            "UPDATE em_websync_stalls SET acknowledged_revision = ?, acknowledged_hash = ? " +
                                "WHERE stall_id = ? AND acknowledged_revision < ?"
                        ).use {
                            it.setLong(1, entry.revision); it.setString(2, entry.hash); it.setString(3, entry.stallId)
                            it.setLong(4, entry.revision); it.executeUpdate()
                        }
                    }
                    setState(connection, "last_full_success", at.toString())
                }
                removed
            }
        }
    }

    fun retry(delivery: PendingDelivery, retryAt: Long) = transaction { connection ->
        val table = if (delivery.kind == DeliveryKind.FULL) "em_websync_full" else "em_websync_stalls"
        val idColumn = if (delivery.kind == DeliveryKind.FULL) "snapshot_revision" else "revision"
        connection.prepareStatement(
            "UPDATE $table SET retry_at = ?, attempt_count = attempt_count + 1 WHERE $idColumn = ? AND pending_event_id = ?"
        ).use { it.setLong(1, retryAt); it.setLong(2, delivery.revision); it.setString(3, delivery.eventId); it.executeUpdate() }
    }

    fun retryNow() = transaction { connection ->
        connection.createStatement().use { it.executeUpdate("UPDATE em_websync_full SET retry_at = 0") }
        connection.createStatement().use { it.executeUpdate("UPDATE em_websync_stalls SET retry_at = 0 WHERE pending_body IS NOT NULL") }
    }

    fun status(): OutboxStatus = dataSource.connection.use { connection ->
        val pendingStalls = scalarLong(connection, "SELECT COUNT(*) FROM em_websync_stalls WHERE pending_body IS NOT NULL").toInt()
        val pendingFull = scalarLong(connection, "SELECT COUNT(*) FROM em_websync_full") > 0
        val oldestStall = nullableLong(connection,
            "SELECT MIN(pending_since) FROM em_websync_stalls WHERE pending_body IS NOT NULL")
        val oldestFull = nullableLong(connection, "SELECT MIN(pending_since) FROM em_websync_full")
        val oldest = listOfNotNull(oldestStall, oldestFull).minOrNull()
        OutboxStatus(pendingStalls, pendingFull, oldest,
            state(connection, "snapshot_revision")?.toLongOrNull() ?: 0L,
            state(connection, "last_full_success")?.toLongOrNull(),
            nullableLong(connection, "SELECT MAX(success_at) FROM em_websync_stalls"))
    }

    private data class StallRow(val revision: Long)
    private data class StallWrite(val id: String, val revision: Long, val hash: String, val body: ByteArray?, val eventId: String?)

    private fun stallRow(connection: Connection, id: String): StallRow? = connection.prepareStatement(
        "SELECT revision FROM em_websync_stalls WHERE stall_id = ?"
    ).use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use { if (it.next()) StallRow(it.getLong(1)) else null }
    }

    private fun insertStall(connection: Connection, w: StallWrite) {
        connection.prepareStatement(
            "INSERT INTO em_websync_stalls (stall_id, revision, latest_hash, pending_body, pending_event_id, pending_since, retry_at, " +
                "attempt_count, acknowledged_revision, acknowledged_hash, success_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, NULL, NULL)"
        ).use {
            it.setString(1, w.id); it.setLong(2, w.revision); it.setString(3, w.hash); it.setBytes(4, w.body); it.setString(5, w.eventId)
            if (w.body == null) {
                it.setNull(6, java.sql.Types.BIGINT); it.setNull(7, java.sql.Types.BIGINT)
            } else {
                val now = System.currentTimeMillis(); it.setLong(6, now); it.setLong(7, now)
            }
            it.executeUpdate()
        }
    }

    private fun updatePendingStall(connection: Connection, w: StallWrite) {
        connection.prepareStatement(
            "UPDATE em_websync_stalls SET revision = ?, latest_hash = ?, pending_body = ?, pending_event_id = ?, " +
                "pending_since = ?, retry_at = ?, attempt_count = 0 WHERE stall_id = ?"
        ).use {
            it.setLong(1, w.revision); it.setString(2, w.hash); it.setBytes(3, w.body!!); it.setString(4, w.eventId)
            val now = System.currentTimeMillis(); it.setLong(5, now); it.setLong(6, now); it.setString(7, w.id); it.executeUpdate()
        }
    }

    private fun updateFullIncluded(connection: Connection, entry: IncludedRevision) {
        connection.prepareStatement(
            "UPDATE em_websync_stalls SET revision = ?, latest_hash = ?, pending_body = NULL, pending_event_id = NULL, " +
                "pending_since = NULL, retry_at = NULL, attempt_count = 0 WHERE stall_id = ?"
        ).use { it.setLong(1, entry.revision); it.setString(2, entry.hash); it.setString(3, entry.stallId); it.executeUpdate() }
    }

    private fun state(connection: Connection, key: String): String? = connection.prepareStatement(
        "SELECT state_value FROM em_websync_state WHERE state_key = ?"
    ).use { it.setString(1, key); it.executeQuery().use { result -> if (result.next()) result.getString(1) else null } }

    private fun setState(connection: Connection, key: String, value: String) {
        val updated = connection.prepareStatement("UPDATE em_websync_state SET state_value = ? WHERE state_key = ?").use {
            it.setString(1, value); it.setString(2, key); it.executeUpdate()
        }
        if (updated == 0) connection.prepareStatement(
            "INSERT INTO em_websync_state (state_key, state_value) VALUES (?, ?)"
        ).use { it.setString(1, key); it.setString(2, value); it.executeUpdate() }
    }

    private fun scalarLong(connection: Connection, sql: String): Long =
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { it.next(); it.getLong(1) } }
    private fun nullableLong(connection: Connection, sql: String): Long? =
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { result ->
            result.next(); result.getLong(1).let { if (result.wasNull()) null else it }
        } }

    private fun hasPendingFull(connection: Connection): Boolean =
        scalarLong(connection, "SELECT COUNT(*) FROM em_websync_full WHERE singleton_id = 1") > 0

    /**
     * Execute [block] in a JDBC transaction.
     *
     * SAFETY NOTE: This toggles [Connection.autoCommit]. HikariCP resets
     * autoCommit on [Connection.close] (which [use] calls when the block
     * exits), but the [finally] restoration to the exact prior value is kept as a belt-and-suspenders
     * guard. If you observe stalled transactions in other EM components, this
     * pattern is a candidate — the EnthusiaMarket codebase has seen
     * HikariCP autoCommit leak issues in the past.
     */
    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        val priorAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try { block(connection).also { connection.commit() } }
        catch (e: Exception) { connection.rollback(); throw e }
        finally { connection.autoCommit = priorAutoCommit }
    }

    private fun List<PublicStall>.sortedByNaturalId(): List<PublicStall> = sortedBy { it.id.removePrefix("stall").toInt() }
}
