package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import org.fossify.messages.models.DiagnosticCounter
import org.fossify.messages.models.ForwardingShadowAttempt
import org.fossify.messages.models.ForwardingShadowDelivery
import org.fossify.messages.models.MessageOperation
import org.fossify.messages.models.MessageOperationStep

@Dao
interface ShadowDaos {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperation(operation: MessageOperation)

    @Update
    suspend fun updateOperation(operation: MessageOperation)

    @Query("SELECT * FROM message_operations WHERE operation_id = :operationId")
    suspend fun getOperation(operationId: String): MessageOperation?

    @Insert
    suspend fun insertStep(step: MessageOperationStep)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDelivery(delivery: ForwardingShadowDelivery)

    @Update
    suspend fun updateDelivery(delivery: ForwardingShadowDelivery)

    @Query("SELECT * FROM forwarding_deliveries WHERE operation_id = :operationId AND channel = :channel")
    suspend fun getDelivery(operationId: String, channel: String): ForwardingShadowDelivery?

    @Query("SELECT * FROM forwarding_deliveries WHERE delivery_id = :deliveryId")
    suspend fun getDeliveryById(deliveryId: String): ForwardingShadowDelivery?

    @Query("SELECT * FROM forwarding_deliveries WHERE state = 'RUNNING'")
    suspend fun findRunningDeliveries(): List<ForwardingShadowDelivery>

    @Query("UPDATE forwarding_deliveries SET state = :state, updated_at = :now WHERE delivery_id = :deliveryId")
    suspend fun updateDeliveryState(deliveryId: String, state: String, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM forwarding_deliveries WHERE state = 'DELIVERED' AND created_at >= :startOfDay")
    suspend fun getDeliveredCountSince(startOfDay: Long): Int

    @Query("SELECT COUNT(*) FROM forwarding_deliveries WHERE state = 'FAILED' AND created_at >= :startOfDay")
    suspend fun getFailedCountSince(startOfDay: Long): Int

    @Query("SELECT COUNT(*) FROM forwarding_deliveries WHERE state = 'RUNNING' AND created_at >= :startOfDay")
    suspend fun getPendingCountSince(startOfDay: Long): Int

    @Query("SELECT * FROM forwarding_deliveries ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentDeliveries(limit: Int = 50): List<ForwardingShadowDelivery>

    @Query("SELECT DISTINCT channel FROM forwarding_deliveries")
    suspend fun getAllChannels(): List<String>

    @Query("SELECT * FROM forwarding_deliveries WHERE channel = :channel ORDER BY created_at DESC LIMIT :limit")
    suspend fun getDeliveriesForChannel(channel: String, limit: Int = 100): List<ForwardingShadowDelivery>

    @Query("SELECT * FROM forwarding_attempts ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentAttempts(limit: Int = 50): List<ForwardingShadowAttempt>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttempt(attempt: ForwardingShadowAttempt)

    @Update
    suspend fun updateAttempt(attempt: ForwardingShadowAttempt)

    @Query("SELECT * FROM forwarding_attempts WHERE delivery_id = :deliveryId ORDER BY attempt_number DESC LIMIT 1")
    suspend fun getLatestAttempt(deliveryId: String): ForwardingShadowAttempt?

    @Query("SELECT * FROM operation_diagnostic_counters WHERE bucket_key = :bucketKey AND event_type = :eventType")
    suspend fun getCounter(bucketKey: String, eventType: String): DiagnosticCounter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCounter(counter: DiagnosticCounter)

    @Transaction
    suspend fun incrementCounter(bucketKey: String, eventType: String, now: Long = System.currentTimeMillis()) {
        val existing = getCounter(bucketKey, eventType)
        if (existing == null) {
            upsertCounter(DiagnosticCounter(bucketKey = bucketKey, eventType = eventType, count = 1, updatedAt = now))
        } else {
            upsertCounter(existing.copy(count = existing.count + 1, updatedAt = now))
        }
    }
}
