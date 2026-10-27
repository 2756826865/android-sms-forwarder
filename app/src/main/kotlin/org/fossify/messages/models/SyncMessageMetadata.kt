package org.fossify.messages.models

/** Read-only projections for history diagnostics. Never materialize message content. */
data class SyncMessageMetadata(
    val id: Long,
    val threadId: Long,
    val date: Int,
    val isMMS: Boolean,
    val isScheduled: Boolean,
    val recycled: Boolean,
)

data class SyncMessageCount(val isMMS: Boolean, val isScheduled: Boolean, val count: Long)
