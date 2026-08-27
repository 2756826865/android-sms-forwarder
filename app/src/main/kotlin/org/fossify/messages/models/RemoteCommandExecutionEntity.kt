package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "remote_command_executions",
    indices = [
        Index(
            value = ["source_type", "source_message_key", "payload_hmac"],
            unique = true,
            name = "index_remote_commands_idempotency"
        ),
        Index(
            value = ["execution_state", "created_at"],
            name = "index_remote_commands_state_created_at"
        ),
        Index(
            value = ["source_type", "source_message_key"],
            name = "index_remote_commands_source"
        ),
        Index(
            value = ["requester_hmac"],
            name = "index_remote_commands_requester_hmac"
        )
    ]
)
data class RemoteCommandExecutionEntity(
    @PrimaryKey
    @ColumnInfo(name = "command_id")
    val commandId: String,

    @ColumnInfo(name = "source_type")
    val sourceType: String,

    @ColumnInfo(name = "source_message_key")
    val sourceMessageKey: String,

    @ColumnInfo(name = "command_type")
    val commandType: String,

    @ColumnInfo(name = "target_hmac")
    val targetHmac: String?,

    @ColumnInfo(name = "payload_hmac")
    val payloadHmac: String,

    @ColumnInfo(name = "payload_length")
    val payloadLength: Int,

    @ColumnInfo(name = "requested_sim_mode")
    val requestedSimMode: Int,

    @ColumnInfo(name = "requester_hmac")
    val requesterHmac: String,

    @ColumnInfo(name = "received_at")
    val receivedAt: Long,

    @ColumnInfo(name = "authorized")
    val authorized: Boolean,

    @ColumnInfo(name = "authorization_reason")
    val authorizationReason: String,

    @ColumnInfo(name = "execution_state")
    val executionState: String,

    @ColumnInfo(name = "outbox_task_id")
    val outboxTaskId: String? = null,

    @ColumnInfo(name = "send_operation_id")
    val sendOperationId: String? = null,

    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,

    @ColumnInfo(name = "error_class")
    val errorClass: String? = null,

    @ColumnInfo(name = "error_hmac")
    val errorHmac: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
