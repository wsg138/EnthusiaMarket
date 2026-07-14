package net.badgersmc.em.websync

import kotlin.math.min

class TrailingDebounce(
    private val delayMillis: Long,
    private val maximumMillis: Long,
) {
    private data class Window(val firstAt: Long, var dueAt: Long)
    private val windows = linkedMapOf<String, Window>()

    fun mark(key: String, now: Long) {
        val window = windows[key]
        if (window == null) windows[key] = Window(now, now + delayMillis)
        else window.dueAt = min(now + delayMillis, window.firstAt + maximumMillis)
    }

    fun firstReady(now: Long): String? = windows.entries.firstOrNull { it.value.dueAt <= now }?.key
    fun remove(key: String) { windows.remove(key) }
    fun clear() = windows.clear()
}
