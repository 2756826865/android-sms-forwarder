package org.fossify.messages.remote

/** Counts every disconnected attempt, including asynchronous WebSocket failures. */
internal class StreamReconnectBackoff(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var nextDelay = 5_000L
    private var connectedAt: Long? = null

    @Synchronized
    fun beginAttempt() {
        connectedAt = null
    }

    @Synchronized
    fun onConnected() {
        if (connectedAt == null) connectedAt = clockMillis()
    }

    @Synchronized
    fun nextDelayMillis(): Long {
        // A handshake followed by immediate failure is not a stable recovery.
        if (connectedAt?.let { clockMillis() - it >= 60_000L } == true) {
            nextDelay = 5_000L
        }
        connectedAt = null
        val delay = nextDelay
        nextDelay = (nextDelay * 2).coerceAtMost(60_000L)
        return delay
    }
}
