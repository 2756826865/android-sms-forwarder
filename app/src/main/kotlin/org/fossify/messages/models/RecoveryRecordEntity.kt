package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recovery_records",
    indices = [
        Index(
            value = ["scan_time"],
            name = "index_recovery_records_scan_time"
        ),
        Index(
            value = ["object_type", "object_id"],
            name = "index_recovery_records_object"
        )
    ]
)
data class RecoveryRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "scan_time")
    val scanTime: Long,

    @ColumnInfo(name = "trigger_source")
    val triggerSource: String,

    @ColumnInfo(name = "object_type")
    val objectType: String,

    @ColumnInfo(name = "object_id")
    val objectId: String,

    @ColumnInfo(name = "initial_status")
    val initialStatus: String,

    @ColumnInfo(name = "recovered_status")
    val recoveredStatus: String,

    @ColumnInfo(name = "action_taken")
    val actionTaken: String,

    @ColumnInfo(name = "detail_message")
    val detailMessage: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
