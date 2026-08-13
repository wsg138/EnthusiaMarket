package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketBlacklistRemoval
import net.enthusia.market.api.moderation.MarketBlacklistRequest
import net.enthusia.market.api.moderation.MarketBlacklistResult
import net.enthusia.market.api.moderation.MarketConfiscationApproval
import net.enthusia.market.api.moderation.MarketModerationApi
import net.enthusia.market.api.moderation.MarketOperationRecord
import net.enthusia.market.api.moderation.MarketOperationRequest
import net.enthusia.market.api.moderation.MarketOperationResult
import net.enthusia.market.api.moderation.MarketRestoreRequest
import net.enthusia.market.api.moderation.MarketStallRecord
import net.enthusia.market.api.moderation.StallBlacklistState
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounded asynchronous Bukkit service facade over the durable JDBC store. */
internal class MarketModerationProvider(
    private val store: JdbcMarketModerationStore,
    private val gate: DurableMarketMutationGate,
    private val regionAccess: MarketRegionAccessCoordinator,
    private val executor: ExecutorService = providerExecutor(),
) : MarketModerationApi, AutoCloseable {
    private val closed = AtomicBoolean()

    override fun apiVersion(): Int = MarketModerationApi.API_VERSION

    override fun findStalls(playerId: UUID): CompletionStage<List<MarketStallRecord>> =
        submit { store.findStalls(playerId) }

    override fun getStallBlacklist(playerId: UUID): CompletionStage<Optional<StallBlacklistState>> =
        submit { store.getBlacklist(playerId) }

    override fun canAcquire(playerId: UUID): CompletionStage<Boolean> = submit { store.canAcquire(playerId) }

    override fun prepare(request: MarketOperationRequest): CompletionStage<MarketOperationResult> {
        if (!gate.reserve(request.stallId(), request.operationId())) {
            return CompletableFuture.completedFuture(
                MarketOperationResult(
                    MarketOperationResult.Status.CONFLICT,
                    Optional.empty(),
                    "Market stall is reserved by another moderation operation",
                ),
            )
        }
        return submit { store.prepare(request) }.whenComplete { result, failure ->
            if (failure != null || result.operation().map { !it.state().keepsReservation() }.orElse(true)) {
                gate.release(request.stallId(), request.operationId())
            }
        }
    }

    override fun confiscate(approval: MarketConfiscationApproval): CompletionStage<MarketOperationResult> =
        submit { confiscateWithRegionFence(approval) }.thenApply(::reconcileGate)

    override fun restore(request: MarketRestoreRequest): CompletionStage<MarketOperationResult> =
        submit { restoreWithRegionAccess(request) }.thenApply(::reconcileGate)

    override fun release(
        operationId: UUID,
        expectedSnapshotChecksum: String,
    ): CompletionStage<MarketOperationResult> =
        submit { store.release(operationId, expectedSnapshotChecksum) }.thenApply(::reconcileGate)

    override fun findOperation(operationId: UUID): CompletionStage<Optional<MarketOperationRecord>> =
        submit { store.findOperation(operationId) }

    override fun applyBlacklist(request: MarketBlacklistRequest): CompletionStage<MarketBlacklistResult> =
        submit { store.applyBlacklist(request) }

    override fun removeBlacklist(removal: MarketBlacklistRemoval): CompletionStage<MarketBlacklistResult> =
        submit { store.removeBlacklist(removal) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdown()
        if (!executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }

    private fun <T> submit(action: () -> T): CompletionStage<T> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Market moderation provider is closed"))
        }
        return CompletableFuture.supplyAsync(action, executor)
    }

    private fun reconcileGate(result: MarketOperationResult): MarketOperationResult {
        result.operation().ifPresent { operation ->
            if (operation.state().keepsReservation()) {
                gate.reserve(operation.stallId(), operation.operationId())
            } else {
                gate.release(operation.stallId(), operation.operationId())
            }
        }
        return result
    }

    private fun confiscateWithRegionFence(approval: MarketConfiscationApproval): MarketOperationResult {
        val operation = store.findOperation(approval.operationId()).orElse(null)
        val shouldClear = operation != null &&
            operation.snapshotChecksum() == approval.expectedSnapshotChecksum() &&
            operation.state() in setOf(
                MarketOperationRecord.State.PREPARED,
                MarketOperationRecord.State.MODERATION_HOLD,
            )
        if (!shouldClear) return store.confiscate(approval)

        val snapshot = try {
            checkNotNull(store.regionAccess(approval.operationId()))
        } catch (_: MarketModerationConflict) {
            return store.confiscate(approval)
        }
        regionAccess.clear(snapshot)
        val result = try {
            store.confiscate(approval)
        } catch (failure: Exception) {
            compensateFailedConfiscation(approval.operationId(), snapshot, failure)
            throw failure
        }
        if (!result.isHeldOrReplay()) {
            regionAccess.restore(snapshot)
        }
        return result
    }

    private fun compensateFailedConfiscation(
        operationId: UUID,
        snapshot: MarketRegionAccessSnapshot,
        failure: Exception,
    ) {
        val state = runCatching { store.findOperation(operationId).orElse(null)?.state() }.getOrNull()
        if (state != MarketOperationRecord.State.PREPARED) return
        runCatching { regionAccess.restore(snapshot) }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
    }

    private fun restoreWithRegionAccess(request: MarketRestoreRequest): MarketOperationResult {
        val snapshot = try {
            store.regionAccess(request.operationId())
        } catch (_: MarketModerationConflict) {
            return store.restore(request)
        }
        val result = store.restore(request)
        val restored = result.operation().map { it.state() == MarketOperationRecord.State.RESTORED }.orElse(false)
        if (restored && snapshot != null) regionAccess.restore(snapshot)
        return result
    }

    private fun MarketOperationResult.isHeldOrReplay(): Boolean =
        status() == MarketOperationResult.Status.HELD ||
            operation().map { it.state() == MarketOperationRecord.State.MODERATION_HOLD }.orElse(false)

    private fun MarketOperationRecord.State.keepsReservation(): Boolean = when (this) {
        MarketOperationRecord.State.PREPARED,
        MarketOperationRecord.State.MODERATION_HOLD,
        MarketOperationRecord.State.QUARANTINED,
        -> true
        MarketOperationRecord.State.RESTORED,
        MarketOperationRecord.State.RELEASED,
        -> false
    }

    private companion object {
        const val SHUTDOWN_SECONDS = 5L

        fun providerExecutor(): ExecutorService {
            val counter = AtomicInteger()
            return Executors.newFixedThreadPool(2) { task ->
                Thread(task, "enthusia-market-moderation-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                    contextClassLoader = null
                }
            }
        }
    }
}
