package org.fossify.messages.helpers

/** Evidence attached only to an actually posted thread notification. No SMS body is stored here. */
internal data class SmsNotificationStamp(
    val threadId: Long,
    val messageId: Long,
    val dateSeconds: Long,
) {
    fun isSupersededBy(posted: SmsNotificationStamp?): Boolean =
        posted != null &&
            posted.threadId == threadId &&
            dateSeconds > 0L &&
            posted.dateSeconds > 0L &&
            (posted.dateSeconds > dateSeconds ||
                (posted.dateSeconds == dateSeconds && posted.messageId > messageId))
}
