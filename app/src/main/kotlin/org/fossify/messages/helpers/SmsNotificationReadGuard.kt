package org.fossify.messages.helpers

/** Serialize publication with visible-thread read acknowledgement, using bounded lock storage. */
internal object SmsNotificationReadGuard {
    private const val STRIPES = 32
    private val locks = Array(STRIPES) { Any() }
    fun lock(threadId: Long): Any = locks[(threadId.hashCode() and Int.MAX_VALUE) % locks.size]
}
