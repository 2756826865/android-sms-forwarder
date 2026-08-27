package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "outbox_tasks",
    indices = [
        Index(
            value = ["state", "next_retry_at"],
            name = "index_outbox_tasks_state_next_retry"
        ),
        Index(
            value = ["source_type", "source_id"],
            name = "index_outbox_tasks_source"
        ),
        Index(
            value = ["locked_by", "lock_expires_at"],
            name = "index_outbox_tasks_locked"
        )
    ]
)
data class OutboxTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String,

    @ColumnInfo(name = "task_type")
    val taskType: String,

    @ColumnInfo(name = "source_type")
    val sourceType: String,

    @ColumnInfo(name = "source_id")
    val sourceId: String,

    @ColumnInfo(name = "payload_hmac")
    val payloadHmac: String,

    @ColumnInfo(name = "payload_payload")
    val payloadPayload: String? = null,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "max_attempts")
    val maxAttempts: Int = 3,

    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long = 0L,

    @ColumnInfo(name = "last_error_class")
    val lastErrorClass: String? = null,

    @ColumnInfo(name = "last_error_hmac")
    val lastErrorHmac: String? = null,

    @ColumnInfo(name = "locked_by")
    val lockedBy: String? = null,

    @ColumnInfo(name = "lock_expires_at")
    val lockExpiresAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
