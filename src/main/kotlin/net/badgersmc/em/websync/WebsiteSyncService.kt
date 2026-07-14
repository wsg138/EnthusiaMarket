package net.badgersmc.em.websync

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

class WebsiteSyncDirtyRelay : WebsiteSyncDirtySink {
    @Volatile var target: WebsiteSyncDirtySink? = null
    override fun markDirty(stallId: String) { target?.markDirty(stallId) }
}

data class WebsiteSyncStatusView(
    val configuredEnabled: Boolean,
    val active: Boolean,
    val secretConfigured: Boolean,
    val validation: String,
    val pendingStalls: Int,
    val pendingFull: Boolean,
    val oldestPendingAgeMillis: Long?,
    val snapshotRevision: Long,
    val lastFullSuccess: Long?,
    val lastStallSuccess: Long?,
    val errorCategory: String?,
)

@Suppress("TooManyFunctions")
class WebsiteSyncService(
    private val plugin: JavaPlugin,
    private val configLoader: WebsiteSyncConfigLoader,
    private val outbox: WebsiteSyncOutbox?,
    private val projector: PublicSnapshotProjector,
    private val migrationFailure: Boolean = false,
) : WebsiteSyncDirtySink, AutoCloseable {
    private val generations = HashMap<String, Long>()
    private var dirty = TrailingDebounce(250, 2000)
    private val executor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "EnthusiaMarket-WebsiteSync").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val deliveryInFlight = AtomicBoolean(false)
    private var fullCapture: IncrementalFullCapture? = null
    private var config: WebsiteSyncConfig? = null
    @Volatile private var active = false
    @Volatile private var paused = true
    @Volatile private var validation = "not_validated"
    @Volatile private var errorCategory: String? = if (migrationFailure) "persistence_migration" else null
    @Volatile private var cachedOutbox = OutboxStatus(0, false, null, 0, null, null)
    private var tickTask: org.bukkit.scheduler.BukkitTask? = null
    private var reconciliationTask: org.bukkit.scheduler.BukkitTask? = null

    fun start() {
        val result = configLoader.load(startup = true)
        config = result.config
        if (result.config == null) {
            validation = "invalid_configuration"
            errorCategory = "configuration"
            return
        }
        validation = "configuration_valid"
        dirty = TrailingDebounce(result.config.debounce.toMillis(), result.config.maximumDebounce.toMillis())
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::tick), 1L, 1L)
        if (result.config.configuredEnabled && result.config.secretConfigured && outbox != null) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable(::beginEnableReconciliation),
                durationTicks(result.config.startupDelay))
        }
    }

    override fun markDirty(stallId: String) {
        if (!Bukkit.isPrimaryThread()) {
            runCatching { Bukkit.getScheduler().runTask(plugin, Runnable { markDirty(stallId) }) }
            return
        }
        if (!active || !isCanonicalId(stallId)) return
        generations[stallId] = (generations[stallId] ?: 0L) + 1L
        dirty.mark(stallId, System.currentTimeMillis())
    }

    fun enable(): WebsiteSyncConfigResult {
        val result = configLoader.setEnabled(true)
        config = result.config ?: configLoader.current()
        if (result.config != null && result.config.secretConfigured && outbox != null) beginEnableReconciliation()
        else {
            active = false
            errorCategory = if (outbox == null) "persistence_migration" else "secret_missing"
        }
        return result
    }

    fun disable(): WebsiteSyncConfigResult {
        val result = configLoader.setEnabled(false)
        config = result.config ?: configLoader.current()
        active = false
        paused = true
        fullCapture = null
        dirty.clear()
        reconciliationTask?.cancel()
        return result
    }

    fun setSecret(value: String): WebsiteSyncConfigResult = configLoader.setSecret(value).also {
        config = it.config ?: configLoader.current()
    }
    fun clearSecret(): WebsiteSyncConfigResult = configLoader.clearSecret().also {
        config = it.config ?: configLoader.current(); active = false; paused = true; errorCategory = "secret_missing"
    }

    fun requestFull(): Boolean {
        if (!active || outbox == null) return false
        beginFullCapture()
        return true
    }

    fun retry(): Boolean {
        if (!active || outbox == null) return false
        paused = false
        errorCategory = null
        submit { outbox.retryNow(); main(::pump) }
        return true
    }

    fun authenticatedTest(callback: (Boolean, String) -> Unit) {
        val cfg = config
        if (cfg == null || !cfg.secretConfigured || outbox == null) {
            callback(false, "not_configured"); return
        }
        submit {
            val outcome = MarketHttpClient(cfg).authenticatedTest(outbox.serverEpoch())
            main { callback(outcome is DeliveryOutcome.Success, outcome.safeCategory()) }
        }
    }

    fun validateLive(): List<String> = projector.validateLive().also {
        validation = if (it.isEmpty()) "valid" else "invalid"
    }

    fun validateLiveReport(): PublicSnapshotProjector.ValidationResult = projector.validateLiveReport().also {
        validation = if (it.errors.isEmpty()) "valid" else "invalid"
    }

    fun status(): WebsiteSyncStatusView {
        val cfg = config
        val now = System.currentTimeMillis()
        return WebsiteSyncStatusView(
            configuredEnabled = cfg?.configuredEnabled == true,
            active = active,
            secretConfigured = cfg?.secretConfigured == true,
            validation = validation,
            pendingStalls = cachedOutbox.pendingStalls,
            pendingFull = cachedOutbox.pendingFull,
            oldestPendingAgeMillis = cachedOutbox.oldestPendingAt?.let { (now - it).coerceAtLeast(0) },
            snapshotRevision = cachedOutbox.snapshotRevision,
            lastFullSuccess = cachedOutbox.lastFullSuccess,
            lastStallSuccess = cachedOutbox.lastStallSuccess,
            errorCategory = errorCategory,
        )
    }

    private fun beginEnableReconciliation() {
        check(Bukkit.isPrimaryThread())
        val cfg = config ?: return
        if (!cfg.configuredEnabled || !cfg.secretConfigured || outbox == null) return
        active = true
        paused = true
        errorCategory = null
        beginFullCapture()
        reconciliationTask?.cancel()
        reconciliationTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (active) beginFullCapture()
        }, durationTicks(cfg.reconciliation), durationTicks(cfg.reconciliation))
    }

    private fun beginFullCapture() {
        paused = true
        val errors = validateLive()
        if (errors.isNotEmpty()) {
            fullCapture = null
            errorCategory = "validation"
            return
        }
        fullCapture = IncrementalFullCapture(
            generation = { generations[it] ?: 0L },
            capture = projector::capture,
        )
    }

    private fun tick() {
        if (!active) return
        val capture = fullCapture
        if (capture != null) {
            try {
                capture.tick()
                if (capture.complete) finishFullCapture(capture)
            } catch (_: Exception) {
                fullCapture = null
                paused = true
                errorCategory = "snapshot_capture"
            }
            return
        }
        if (paused) return
        val now = System.currentTimeMillis()
        val id = dirty.firstReady(now) ?: return
        val expectedGeneration = generations[id] ?: 0L
        val stall = try { projector.capture(id) } catch (_: Exception) {
            errorCategory = "stall_capture"; dirty.remove(id); return
        }
        dirty.remove(id)
        submit {
            try {
                outbox?.enqueueStall(stall)
                main {
                    if ((generations[id] ?: 0L) > expectedGeneration) markDirty(id)
                    refreshStatus(); pump()
                }
            } catch (_: Exception) { main { markDirty(id); errorCategory = "persistence" } }
        }
    }

    private fun finishFullCapture(capture: IncrementalFullCapture) {
        fullCapture = null
        val snapshots = capture.stalls.toList()
        val capturedGenerations = capture.capturedGenerations.toMap()
        submit {
            try {
                outbox?.enqueueFull(snapshots)
                main {
                    capturedGenerations.forEach { (id, generation) ->
                        if ((generations[id] ?: 0L) > generation) markDirty(id) else dirty.remove(id)
                    }
                    paused = false
                    validation = "valid"
                    refreshStatus()
                    pump()
                }
            } catch (_: Exception) {
                main { paused = true; errorCategory = "persistence" }
            }
        }
    }

    private fun pump() {
        val box = outbox ?: return
        if (!active || paused || !deliveryInFlight.compareAndSet(false, true)) return
        val cfg = config ?: run { deliveryInFlight.set(false); return }
        submit {
            val delivery = runCatching { box.nextReady() }.getOrNull()
            if (delivery == null) {
                deliveryInFlight.set(false); refreshStatus(); return@submit
            }
            val outcome = MarketHttpClient(cfg).deliver(delivery)
            when (outcome) {
                DeliveryOutcome.Success -> runCatching { box.acknowledge(delivery) }
                is DeliveryOutcome.Retry -> {
                    val exponential = cfg.initialRetry.toMillis() * (1L shl delivery.attemptCount.coerceAtMost(16))
                    val delay = outcome.retryAfterMillis ?: exponential.coerceAtMost(cfg.maximumRetry.toMillis())
                    runCatching { box.retry(delivery, System.currentTimeMillis() + delay) }
                }
                is DeliveryOutcome.Reconcile -> main { errorCategory = outcome.category; beginFullCapture() }
                is DeliveryOutcome.Pause -> main { paused = true; errorCategory = outcome.category }
            }
            deliveryInFlight.set(false)
            refreshStatus()
            if (outcome is DeliveryOutcome.Success) main(::pump)
        }
    }

    private fun refreshStatus() {
        val box = outbox ?: return
        if (Thread.currentThread().name == "EnthusiaMarket-WebsiteSync") {
            runCatching { cachedOutbox = box.status() }
        } else submit { runCatching { cachedOutbox = box.status() } }
    }

    private fun submit(task: () -> Unit): Boolean = try {
        executor.execute(task); true
    } catch (_: RejectedExecutionException) {
        errorCategory = "executor_saturated"; false
    }

    private fun main(task: () -> Unit) {
        if (Bukkit.isPrimaryThread()) task() else runCatching { Bukkit.getScheduler().runTask(plugin, Runnable(task)) }
    }

    private fun DeliveryOutcome.safeCategory(): String = when (this) {
        DeliveryOutcome.Success -> "authenticated"
        is DeliveryOutcome.Retry -> "temporary_failure"
        is DeliveryOutcome.Reconcile -> category
        is DeliveryOutcome.Pause -> category
    }

    override fun close() {
        active = false; paused = true
        tickTask?.cancel(); reconciliationTask?.cancel()
        executor.shutdownNow()
    }

    companion object {
        private fun durationTicks(duration: Duration): Long = (duration.toMillis() / 50L).coerceAtLeast(1L)
        private fun isCanonicalId(id: String): Boolean = id.removePrefix("stall").toIntOrNull() in 1..71 && id.startsWith("stall")
    }
}
