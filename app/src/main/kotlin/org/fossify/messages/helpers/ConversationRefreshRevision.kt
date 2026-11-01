package org.fossify.messages.helpers

/** A slow Provider query must not publish over a local commit observed after it began. */
internal object ConversationRefreshRevision {
    private val value = java.util.concurrent.atomic.AtomicLong()

    fun current(): Long = value.get()

    fun changed() {
        value.incrementAndGet()
    }
}
