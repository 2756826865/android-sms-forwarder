package org.fossify.messages.messaging

/** First-run history protection and overlap are independent from the resumable page cursor. */
internal object SmsRecoveryPlan {
    private const val OVERLAP_MS = 60_000L

    fun floor(stored: Long, installAt: Long, now: Long): Long = if (stored <= 0) now else installAt

    fun since(stored: Long, requested: Long, floor: Long, now: Long): Long =
        maxOf(floor, minOf(if (stored > 0) stored - OVERLAP_MS else now, requested))
}
