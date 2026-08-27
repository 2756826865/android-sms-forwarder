package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sms_send_parts",
    indices = [
        Index(value = ["send_operation_id"]),
        Index(value = ["send_operation_id", "part_index"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = SmsSendOperationEntity::class,
            parentColumns = ["send_operation_id"],
            childColumns = ["send_operation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SmsSendPartEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "part_id")
    val partId: Long = 0,

    @ColumnInfo(name = "send_operation_id")
    val sendOperationId: String,

    @ColumnInfo(name = "part_index")
    val partIndex: Int,

    @ColumnInfo(name = "part_count")
    val partCount: Int,

    @ColumnInfo(name = "sent_request_identity")
    val sentRequestIdentity: Int? = null,

    @ColumnInfo(name = "delivered_request_identity")
    val deliveredRequestIdentity: Int? = null,

    @ColumnInfo(name = "sent_state")
    val sentState: String? = null,

    @ColumnInfo(name = "sent_result_code")
    val sentResultCode: Int? = null,

    @ColumnInfo(name = "delivered_state")
    val deliveredState: String? = null,

    @ColumnInfo(name = "delivered_result_code")
    val deliveredResultCode: Int? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sent_at")
    val sentAt: Long? = null,

    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
