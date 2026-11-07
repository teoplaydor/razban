package com.razban.app.diag

class DiagnosticBackoff(
    private val delaysMs: LongArray = longArrayOf(2_000, 5_000, 15_000, 60_000, 300_000),
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var failures = 0
    private var nextAt = 0L

    fun canSend(): Boolean = now() >= nextAt

    fun success() {
        failures = 0
        nextAt = 0L
    }

    fun failure(): Long {
        val delay = delaysMs[failures.coerceAtMost(delaysMs.lastIndex)]
        failures = (failures + 1).coerceAtMost(delaysMs.lastIndex)
        nextAt = now() + delay
        return delay
    }

    fun reset() {
        failures = 0
        nextAt = 0L
    }

    fun pendingDelayMs(): Long = (nextAt - now()).coerceAtLeast(0)
}
