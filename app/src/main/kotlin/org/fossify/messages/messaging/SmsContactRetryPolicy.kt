package org.fossify.messages.messaging

/** WorkManager runAttemptCount is zero for the first execution. */
internal object SmsContactRetryPolicy {
    private const val MAX_TOTAL_ATTEMPTS = 4
    fun shouldRetry(runAttemptCount: Int): Boolean =
        runAttemptCount < MAX_TOTAL_ATTEMPTS - 1
}
