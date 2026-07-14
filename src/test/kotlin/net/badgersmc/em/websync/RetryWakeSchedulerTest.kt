package net.badgersmc.em.websync

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetryWakeSchedulerTest {
    @Test
    fun `earlier work replaces one pending wake without creating a retry storm`() {
        var now = 1_000L
        val tasks = mutableListOf<FakeTask>()
        var wakes = 0
        val scheduler = RetryWakeScheduler(
            clock = { now },
            scheduleTask = { delay, action -> FakeTask(delay, action).also(tasks::add) },
            shouldWake = { true },
            wake = { wakes++ },
        )

        scheduler.schedule(5_000)
        scheduler.schedule(6_000)
        assertEquals(1, tasks.size)
        scheduler.schedule(3_000)
        assertEquals(2, tasks.size)
        assertTrue(tasks[0].cancelled)
        assertEquals(2_000, tasks[1].delay)
        tasks[1].run()
        assertEquals(1, wakes)
        assertEquals(null, scheduler.scheduledAt)
    }

    @Test
    fun `disable or explicit cancellation prevents the wake`() {
        var enabled = true
        lateinit var task: FakeTask
        var wakes = 0
        val scheduler = RetryWakeScheduler(
            clock = { 0 },
            scheduleTask = { delay, action -> FakeTask(delay, action).also { task = it } },
            shouldWake = { enabled },
            wake = { wakes++ },
        )
        scheduler.schedule(1_000)
        enabled = false
        task.run()
        assertEquals(0, wakes)

        enabled = true
        scheduler.schedule(2_000)
        scheduler.cancel()
        assertTrue(task.cancelled)
        task.run()
        assertEquals(0, wakes)
    }

    @Test
    fun `exponential backoff is bounded and valid Retry-After is preserved`() {
        val initial = Duration.ofSeconds(1)
        val maximum = Duration.ofSeconds(8)
        assertEquals(1_000, RetryDelayPolicy.delay(initial, maximum, 0, null))
        assertEquals(2_000, RetryDelayPolicy.delay(initial, maximum, 1, null))
        assertEquals(8_000, RetryDelayPolicy.delay(initial, maximum, 10, null))
        assertEquals(12_345, RetryDelayPolicy.delay(initial, maximum, 4, 12_345))
    }

    private class FakeTask(val delay: Long, private val action: () -> Unit) : CancelableTask {
        var cancelled = false
        override fun cancel() { cancelled = true }
        fun run() { if (!cancelled) action() }
    }
}
