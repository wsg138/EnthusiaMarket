package net.badgersmc.em.websync

class IncrementalFullCapture(
    private val stallIds: List<String> = (1..71).map { "stall$it" },
    private val generation: (String) -> Long,
    private val capture: (String) -> PublicStall,
    private val maximumPerTick: Int = 1,
    private val timeBudgetNanos: Long = 2_000_000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var cursor = 0
    val stalls = mutableListOf<PublicStall>()
    val capturedGenerations = linkedMapOf<String, Long>()
    val complete: Boolean get() = cursor == stallIds.size

    fun tick(): Int {
        val started = nanoTime()
        var processed = 0
        while (canProcess(processed, started)) {
            val id = stallIds[cursor]
            stalls += capture(id)
            capturedGenerations[id] = generation(id)
            cursor++
            processed++
        }
        return processed
    }

    private fun canProcess(processed: Int, started: Long): Boolean {
        if (cursor >= stallIds.size || processed >= maximumPerTick) return false
        return processed == 0 || nanoTime() - started < timeBudgetNanos
    }
}
