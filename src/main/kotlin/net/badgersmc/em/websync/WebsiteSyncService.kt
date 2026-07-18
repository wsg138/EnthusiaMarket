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
    val bedrockHeads: net.badgersmc.em.websync.heads.BedrockHeadStatus? = null,
    val geyserApiAvailable: Boolean = false,
    val bedrockCaptureEnabled: Boolean = false,
    val floodgateCapture: net.badgersmc.em.websync.heads.FloodgateCaptureStatus? = null,
)

@Suppress("TooManyFunctions")
class WebsiteSyncService(
    private val plugin: JavaPlugin,
    private val configLoader: WebsiteSyncConfigLoader,
    private val outbox: WebsiteSyncOutbox?,
    private val projector: PublicSnapshotProjector,
    private val canonical: CanonicalMarketMap,
    private val migrationFailure: Boolean = false,
    private val bedrockHeadStatus: () -> net.badgersmc.em.websync.heads.BedrockHeadStatus? = { null },
    private val geyserStatus: () -> Pair<Boolean, Boolean> = { false to false },
    private val floodgateStatus: () -> net.badgersmc.em.websync.heads.FloodgateCaptureStatus? = { null },
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
    private var httpClientConfig: WebsiteSyncConfig? = null
    private var httpClient: MarketHttpClient? = null
    @Volatile private var active = false
    @Volatile private var paused = true
    @Volatile private var validation = "not_validated"
    @Volatile private var errorCategory: String? = if (migrationFailure) "persistence_migration" else null
    @Volatile private var cachedOutbox = OutboxStatus(0, false, null, 0, null, null)
    private var tickTask: org.bukkit.scheduler.BukkitTask? = null
    private var reconciliationTask: org.bukkit.scheduler.BukkitTask? = null
    private val retryWakeScheduler = RetryWakeScheduler(
        clock = System::currentTimeMillis,
        scheduleTask = { delayMillis, task ->
            val ticks = ((delayMillis + 49L) / 50L).coerceAtLeast(1L)
            val bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable(task), ticks)
            CancelableTask(bukkitTask::cancel)
        },
        shouldWake = { active && !paused },
        wake = ::pump,
    )

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
        cancelRetryWake()
        return result
    }

    fun setSecret(value: String): WebsiteSyncConfigResult = configLoader.setSecret(value).also {
        config = it.config ?: configLoader.current()
        clearHttpClient()
    }
    fun clearSecret(): WebsiteSyncConfigResult = configLoader.clearSecret().also {
        config = it.config ?: configLoader.current(); clearHttpClient()
        active = false; paused = true; errorCategory = "secret_missing"
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
        val submitted = submit { outbox.retryNow(); main(::pump) }
        if (submitted) cancelRetryWake()
        else scheduleRetryWake(System.currentTimeMillis() + EXECUTOR_RECOVERY_DELAY_MILLIS)
        return submitted
    }

    fun authenticatedTest(callback: (Boolean, String) -> Unit) {
        val cfg = config
        if (cfg == null || !cfg.secretConfigured || outbox == null) {
            callback(false, "not_configured"); return
        }
        val submitted = submit {
            val outcome = client(cfg).authenticatedTest(outbox.serverEpoch())
            main { callback(outcome is DeliveryOutcome.Success, outcome.safeCategory()) }
        }
        if (!submitted) callback(false, "executor_saturated")
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
        val geyser = geyserStatus()
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
            bedrockHeads = bedrockHeadStatus(),
            geyserApiAvailable = geyser.first,
            bedrockCaptureEnabled = geyser.second,
            floodgateCapture = floodgateStatus(),
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
        cancelRetryWake()
        val errors = validateLive()
        if (errors.isNotEmpty()) {
            fullCapture = null
            errorCategory = "validation"
            return
        }
        fullCapture = IncrementalFullCapture(
            stallIds = canonical.stallIds,
            generation = { generations[it] ?: 0L },
            capture = projector::capture,
        )
    }

    private fun tick() {
        if (!active) return
        val capture = fullCapture
        if (capture != null) { tickFullCapture(capture); return }
        tickIncrementalStall()
    }

    private fun tickFullCapture(capture: IncrementalFullCapture) {
        try {
            capture.tick()
            if (capture.complete) finishFullCapture(capture)
        } catch (_: Exception) {
            fullCapture = null
            paused = true
            errorCategory = "snapshot_capture"
        }
    }

    private fun tickIncrementalStall() {
        if (paused) return
        val now = System.currentTimeMillis()
        val id = dirty.firstReady(now) ?: return
        val stall = captureStallForSync(id) ?: return
        dirty.remove(id)
        submitStallForDelivery(id, stall, (generations[id] ?: 0L))
    }

    private fun captureStallForSync(id: String): PublicStall? = try {
        projector.capture(id)
    } catch (_: Exception) {
        errorCategory = "stall_capture"; dirty.remove(id); null
    }

    private fun submitStallForDelivery(id: String, stall: PublicStall, expectedGeneration: Long) {
        val submitted = submit {
            try {
                outbox?.enqueueStall(stall)
                main {
                    if ((generations[id] ?: 0L) > expectedGeneration) markDirty(id)
                    refreshStatus(); pump()
                }
            } catch (_: IllegalArgumentException) {
                main { errorCategory = "payload_too_large" }
            } catch (_: Exception) { main { markDirty(id); errorCategory = "persistence" } }
        }
        if (!submitted) {
            markDirty(id)
            errorCategory = "executor_saturated"
        }
    }

    private fun finishFullCapture(capture: IncrementalFullCapture) {
        fullCapture = null
        val snapshots = capture.stalls.toList()
        val capturedGenerations = capture.capturedGenerations.toMap()
        val submitted = submit {
            try {
                outbox?.enqueueFull(snapshots)
                main {
                    capturedGenerations.forEach { (id, generation) ->
                        if ((generations[id] ?: 0L) > generation) markDirty(id) else dirty.remove(id)
                    }
                    paused = false
                    validation = "valid"
                    errorCategory = null
                    refreshStatus()
                    pump()
                }
            } catch (_: IllegalArgumentException) {
                main { paused = true; errorCategory = "payload_too_large" }
            } catch (_: Exception) {
                main { paused = true; errorCategory = "persistence" }
            }
        }
        if (!submitted) {
            fullCapture = capture
            paused = true
            errorCategory = "executor_saturated"
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun pump() {
        val box = outbox ?: return
        if (!active || paused) return
        cancelRetryWake()
        if (!deliveryInFlight.compareAndSet(false, true)) return
        val cfg = config ?: run { deliveryInFlight.set(false); return }
        val submitted = submit {
            val deliveryResult = runCatching { box.nextReady() }
            if (deliveryResult.isFailure) {
                deliveryInFlight.set(false)
                errorCategory = "persistence"
                main { scheduleRetryWake(System.currentTimeMillis() + EXECUTOR_RECOVERY_DELAY_MILLIS) }
                return@submit
            }
            val delivery = deliveryResult.getOrNull()
            if (delivery == null) {
                val nextAttempt = runCatching { box.nextAttemptAt() }
                deliveryInFlight.set(false); refreshStatus()
                if (nextAttempt.isFailure) {
                    errorCategory = "persistence"
                    main { scheduleRetryWake(System.currentTimeMillis() + EXECUTOR_RECOVERY_DELAY_MILLIS) }
                } else if (nextAttempt.getOrNull() != null) {
                    main { scheduleRetryWake(requireNotNull(nextAttempt.getOrNull())) }
                }
                return@submit
            }
            val outcome = client(cfg).deliver(delivery)
            var pumpAgain = false
            var recoveryWake: Long? = null
            when (outcome) {
                DeliveryOutcome.Success -> {
                    val acknowledgement = runCatching { box.acknowledge(delivery) }
                    if (acknowledgement.isSuccess) {
                        errorCategory = null
                        pumpAgain = true
                    } else {
                        errorCategory = "persistence"
                        recoveryWake = System.currentTimeMillis() + EXECUTOR_RECOVERY_DELAY_MILLIS
                    }
                }
                is DeliveryOutcome.Retry -> {
                    val delay = RetryDelayPolicy.delay(
                        cfg.initialRetry, cfg.maximumRetry, delivery.attemptCount, outcome.retryAfterMillis)
                    val retryAt = System.currentTimeMillis() + delay
                    if (runCatching { box.retry(delivery, retryAt) }.isSuccess) recoveryWake = retryAt
                    else {
                        errorCategory = "persistence"
                        recoveryWake = System.currentTimeMillis() + EXECUTOR_RECOVERY_DELAY_MILLIS
                    }
                }
                is DeliveryOutcome.Reconcile -> main { errorCategory = outcome.category; beginFullCapture() }
                is DeliveryOutcome.Pause -> main { paused = true; errorCategory = outcome.category }
            }
            deliveryInFlight.set(false)
            refreshStatus()
            if (pumpAgain) main(::pump)
            else if (recoveryWake != null) main { scheduleRetryWake(requireNotNull(recoveryWake)) }
        }
        if (!submitted) {
            deliveryInFlight.set(false)
            scheduleRetryWake(System.currentTimeMillis() + EXECUTOR_RECOVERY_DELAY_MILLIS)
        }
    }

    private fun scheduleRetryWake(at: Long) {
        check(Bukkit.isPrimaryThread())
        if (!active || paused) return
        retryWakeScheduler.schedule(at)
    }

    private fun cancelRetryWake() {
        retryWakeScheduler.cancel()
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

    @Synchronized
    private fun client(cfg: WebsiteSyncConfig): MarketHttpClient {
        if (httpClient == null || httpClientConfig != cfg) {
            httpClientConfig = cfg
            httpClient = MarketHttpClient(cfg, "EnthusiaMarket/${plugin.pluginMeta.version}")
        }
        return requireNotNull(httpClient)
    }

    @Synchronized
    private fun clearHttpClient() {
        httpClient = null
        httpClientConfig = null
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
        cancelRetryWake()
        clearHttpClient()
        executor.shutdownNow()
    }

    private fun isCanonicalId(id: String): Boolean = id in canonical.stalls

    companion object {
        private const val EXECUTOR_RECOVERY_DELAY_MILLIS = 1_000L
        private fun durationTicks(duration: Duration): Long = (duration.toMillis() / 50L).coerceAtLeast(1L)
    }
}
