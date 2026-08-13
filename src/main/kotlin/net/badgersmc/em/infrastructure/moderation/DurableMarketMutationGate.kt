package net.badgersmc.em.infrastructure.moderation

import net.badgersmc.em.domain.ports.MarketMutationGate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * Process-local fast path backed by the durable lock table. It is populated at
 * startup and reserved before asynchronous provider work is queued, closing the
 * gap where a cached shop could otherwise trade before its frozen row is reloaded.
 */
internal class DurableMarketMutationGate(dataSource: DataSource) : MarketMutationGate {
    private val operationsByStall = ConcurrentHashMap<String, UUID>()

    init {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT stall_id, operation_id FROM market_moderation_locks").use { statement ->
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        operationsByStall[result.getString("stall_id")] = UUID.fromString(
                            result.getString("operation_id"),
                        )
                    }
                }
            }
        }
    }

    override fun isStallLocked(stallId: String): Boolean = operationsByStall.containsKey(stallId)

    fun reserve(stallId: String, operationId: UUID): Boolean =
        operationsByStall.compute(stallId) { _, existing -> existing ?: operationId } == operationId

    fun release(stallId: String, operationId: UUID) {
        operationsByStall.remove(stallId, operationId)
    }
}
