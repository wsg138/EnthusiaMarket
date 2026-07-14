package net.badgersmc.em.websync

import java.time.Duration

fun interface CancelableTask {
    fun cancel()
}

/** Maintains at most one wake-up, replacing it only when earlier work arrives. */
class RetryWakeScheduler(
    private val clock: () -> Long,
    private val scheduleTask: (delayMillis: Long, task: () -> Unit) -> CancelableTask,
    private val shouldWake: () -> Boolean,
    private val wake: () -> Unit,
) {
    private var task: CancelableTask? = null
    var scheduledAt: Long? = null
        private set

    fun schedule(at: Long) {
        val current = scheduledAt
        if (current != null && current <= at && task != null) return
        cancel()
        scheduledAt = at
        task = scheduleTask((at - clock()).coerceAtLeast(0)) {
            task = null
            scheduledAt = null
            if (shouldWake()) wake()
        }
    }

    fun cancel() {
        task?.cancel()
        task = null
        scheduledAt = null
    }
}

object RetryDelayPolicy {
    fun delay(
        initial: Duration,
        maximum: Duration,
        attemptCount: Int,
        retryAfterMillis: Long?,
    ): Long {
        if (retryAfterMillis != null) return retryAfterMillis.coerceAtLeast(0)
        val multiplier = 1L shl attemptCount.coerceIn(0, 16)
        return (initial.toMillis() * multiplier).coerceAtMost(maximum.toMillis())
    }
}
