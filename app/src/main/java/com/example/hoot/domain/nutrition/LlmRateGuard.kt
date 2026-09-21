package com.example.hoot.domain.nutrition

/**
 * Per-run LLM rate guard (pure window math, unit-testable). Tracks call
 * timestamps in a sliding 60-second window; [acquire] suspends (caller's
 * duty via the returned delay) until a slot frees up. Exceeding the budget
 * pauses the drain loop — nothing special is surfaced in the UI beyond the
 * ongoing "Analyzing… n foods left" count.
 *
 * Not thread-safe by itself: the resolver serializes [acquire] through a
 * mutex so two workers cannot both squeeze past a full window.
 */
class LlmRateGuard(
    private val callsPerMinute: Int,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleepMs: suspend (Long) -> Unit
) {
    private val window = ArrayDeque<Long>()
    private val lock = Any()

    init {
        require(callsPerMinute >= 0) { "callsPerMinute must be ≥ 0" }
    }

    /** 0 = unlimited (guard disabled). */
    val unlimited: Boolean get() = callsPerMinute <= 0

    val windowMillis: Long = 60_000L

    /**
     * Blocks (suspending) until one call slot is available, then records it.
     * Returns the total delay waited in ms (0 when immediate) — useful for
     * logging. Calls made at the same instant free their slots together, so
     * one wait can unblock several waiters.
     */
    suspend fun acquire(): Long {
        if (unlimited) return 0L
        var waitedTotal = 0L
        while (true) {
            val wait: Long
            synchronized(lock) {
                prune()
                if (window.size < callsPerMinute) {
                    window.addLast(nowMs())
                    wait = -1L            // acquired
                } else {
                    // Oldest call must age out of the window first.
                    val oldest = window.first()
                    wait = (oldest + windowMillis - nowMs()).coerceAtLeast(1L)
                }
            }
            if (wait < 0) return waitedTotal
            waitedTotal += wait
            sleepMs(wait)
        }
    }

    /** Calls recorded inside the current sliding window (telemetry/tests). */
    fun callsInWindow(): Int = synchronized(lock) { prune(); window.size }

    private fun prune() {
        val cutoff = nowMs() - windowMillis
        while (window.isNotEmpty() && window.first() <= cutoff) window.removeFirst()
    }
}
