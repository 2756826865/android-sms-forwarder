package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "message_operations",
    indices = [
        Index(value = ["direction", "created_at"]),
        Index(value = ["provider_message_id"]),
        Index(value = ["address_hmac"]),
        Index(value = ["body_hmac"])
    ]
)
data class MessageOperation(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "direction") val direction: String, // INCOMING, OUTGOING
    @ColumnInfo(name = "source") val source: String, // BROADCAST, UI, REMOTE_COMMAND, RECOVERY, BULK, SCHEDULED
    
    @ColumnInfo(name = "provider_message_id") val providerMessageId: Long? = null,
    @ColumnInfo(name = "thread_id") val threadId: Long? = null,
    
    @ColumnInfo(name = "address_hmac") val addressHmac: String? = null,
    @ColumnInfo(name = "body_hmac") val bodyHmac: String? = null,
    @ColumnInfo(name = "body_length") val bodyLength: Int? = null,
    
    @ColumnInfo(name = "subscription_id") val subscriptionId: Int? = null,
    @ColumnInfo(name = "pdu_count") val pduCount: Int? = null,
    @ColumnInfo(name = "format") val format: String? = null,
    
    @ColumnInfo(name = "message_timestamp") val messageTimestamp: Long? = null,
    @ColumnInfo(name = "received_at") val receivedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "provider_inserted_at") val providerInsertedAt: Long? = null,
    
    @ColumnInfo(name = "match_confidence") val matchConfidence: Float? = null,
    @ColumnInfo(name = "diagnostic_state") val diagnosticState: String? = null,
    
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null
)

@Entity(
    tableName = "message_operation_steps",
    indices = [Index(value = ["operation_id"])]
)
data class MessageOperationStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "step_type") val stepType: String,
    @ColumnInfo(name = "status") val status: String, // SUCCESS, FAILED, STARTED, OBSERVED
    @ColumnInfo(name = "detail") val detail: String? = null,
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "forwarding_deliveries",
    indices = [Index(value = ["operation_id"])]
)
data class ForwardingShadowDelivery(
    @PrimaryKey @ColumnInfo(name = "delivery_id") val deliveryId: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "channel") val channel: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "forwarding_attempts",
    indices = [Index(value = ["delivery_id"])]
)
data class ForwardingShadowAttempt(
    @PrimaryKey @ColumnInfo(name = "attempt_id") val attemptId: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "delivery_id") val deliveryId: String = "",
    @ColumnInfo(name = "attempt_number") val attemptNumber: Int,
    @ColumnInfo(name = "legacy_worker_id") val legacyWorkerId: String? = null,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "request_started_at") val requestStartedAt: Long? = null,
    @ColumnInfo(name = "request_finished_at") val requestFinishedAt: Long? = null,
    @ColumnInfo(name = "response_received_at") val responseReceivedAt: Long? = null,
    @ColumnInfo(name = "http_status") val httpStatus: Int? = null,
    @ColumnInfo(name = "error_class") val errorClass: String? = null,
    @ColumnInfo(name = "error_hmac") val errorHmac: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "operation_diagnostic_counters",
    indices = [Index(value = ["bucket_key", "event_type"], unique = true)]
)
data class DiagnosticCounter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bucket_key") val bucketKey: String, // e.g., yyyy-MM-dd-HH
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "count") val count: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
