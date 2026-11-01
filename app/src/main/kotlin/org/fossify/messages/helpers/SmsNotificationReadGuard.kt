package org.fossify.messages.helpers

/** Serialize publication with visible-thread read acknowledgement, using bounded lock storage. */
internal object SmsNotificationReadGuard {
    private val locks = Array(32) { Any() }
    fun lock(threadId: Long): Any = locks[(threadId.hashCode() and Int.MAX_VALUE) % locks.size]
}
