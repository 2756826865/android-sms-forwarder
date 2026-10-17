package org.fossify.messages.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamReconnectBackoffTest {
    private var now = 0L
    private val backoff = StreamReconnectBackoff { now }

    @Test fun asynchronousFailuresBackOffAndRemainCapped() {
        val delays = (1..8).map {
            backoff.beginAttempt()
            backoff.nextDelayMillis()
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L, 60_000L), delays)
    }

    @Test fun shortLivedConnectionsDoNotResetBackoff() {
        repeat(4) {
            backoff.beginAttempt()
            backoff.onConnected()
            now += 1_000L
            backoff.nextDelayMillis()
        }
        assertEquals(60_000L, backoff.nextDelayMillis())
    }

    @Test fun stableConnectionRestoresFastRecovery() {
        repeat(5) { backoff.nextDelayMillis() }
        backoff.beginAttempt()
        backoff.onConnected()
        now += 60_000L
        assertEquals(5_000L, backoff.nextDelayMillis())
        assertEquals(10_000L, backoff.nextDelayMillis())
    }

    @Test fun longUnauthenticatedAttemptDoesNotCountAsRecovery() {
        repeat(5) { backoff.nextDelayMillis() }
        backoff.beginAttempt()
        now += 120_000L
        assertEquals(60_000L, backoff.nextDelayMillis())
    }

    @Test fun newAttemptClearsPreviousConnectionTimestamp() {
        backoff.onConnected()
        now += 60_000L
        backoff.beginAttempt()
        backoff.nextDelayMillis()
        assertEquals(10_000L, backoff.nextDelayMillis())
    }

    @Test fun repeatedReadyEventsDoNotPostponeStability() {
        repeat(5) { backoff.nextDelayMillis() }
        backoff.onConnected()
        now += 59_000L
        backoff.onConnected()
        now += 1_000L
        assertEquals(5_000L, backoff.nextDelayMillis())
    }
}
