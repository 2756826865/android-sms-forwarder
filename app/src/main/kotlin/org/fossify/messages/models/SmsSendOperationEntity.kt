package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "sms_send_operations",
    indices = [
        Index(value = ["state", "created_at"]),
        Index(value = ["provider_message_id"]),
        Index(value = ["address_hmac"])
    ]
)
data class SmsSendOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "send_operation_id")
    val sendOperationId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "trigger_type")
    val triggerType: String,

    @ColumnInfo(name = "address_hmac")
    val addressHmac: String? = null,

    @ColumnInfo(name = "body_hmac")
    val bodyHmac: String? = null,

    @ColumnInfo(name = "body_length")
    val bodyLength: Int? = null,

    @ColumnInfo(name = "subscription_id")
    val subscriptionId: Int? = null,

    @ColumnInfo(name = "thread_id")
    val threadId: Long? = null,

    @ColumnInfo(name = "require_delivery_report")
    val requireDeliveryReport: Boolean = false,

    @ColumnInfo(name = "message_uri")
    val messageUri: String? = null,

    @ColumnInfo(name = "provider_message_id")
    val providerMessageId: Long? = null,

    @ColumnInfo(name = "part_count")
    val partCount: Int? = null,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "error_class")
    val errorClass: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "submitting_at")
    val submittingAt: Long? = null,

    @ColumnInfo(name = "submitted_at")
    val submittedAt: Long? = null,

    @ColumnInfo(name = "sent_at")
    val sentAt: Long? = null,

    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long? = null,

    @ColumnInfo(name = "failed_at")
    val failedAt: Long? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
