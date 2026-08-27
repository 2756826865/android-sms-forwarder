package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.models.SmsSendPartEntity

@Dao
interface SmsSendDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperation(operation: SmsSendOperationEntity)

    @Update
    suspend fun updateOperation(operation: SmsSendOperationEntity)

    @Query("SELECT * FROM sms_send_operations WHERE send_operation_id = :operationId")
    suspend fun getOperationById(operationId: String): SmsSendOperationEntity?

    @Query("SELECT * FROM sms_send_operations WHERE provider_message_id = :providerMessageId LIMIT 1")
    suspend fun getOperationByProviderMessageId(providerMessageId: Long): SmsSendOperationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPart(part: SmsSendPartEntity)

    @Update
    suspend fun updatePart(part: SmsSendPartEntity)

    @Query("SELECT * FROM sms_send_parts WHERE send_operation_id = :operationId ORDER BY part_index ASC")
    suspend fun getPartsByOperationId(operationId: String): List<SmsSendPartEntity>

    @Query("UPDATE sms_send_operations SET provider_message_id = :providerMessageId, message_uri = :messageUri, updated_at = :now WHERE send_operation_id = :operationId")
    suspend fun updateProviderAssociation(operationId: String, providerMessageId: Long?, messageUri: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE sms_send_operations SET state = :state, updated_at = :now WHERE send_operation_id = :operationId")
    suspend fun updateState(operationId: String, state: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM sms_send_operations WHERE state = 'SUBMITTING' AND created_at < :cutoff")
    suspend fun findStaleSubmittingOperations(cutoff: Long): List<SmsSendOperationEntity>

    @Query("SELECT * FROM sms_send_operations WHERE state = 'SUBMITTED' AND (submitted_at IS NOT NULL AND submitted_at < :cutoff OR (submitted_at IS NULL AND created_at < :cutoff))")
    suspend fun findStaleSubmittedOperations(cutoff: Long): List<SmsSendOperationEntity>

    @Query("SELECT COUNT(*) FROM sms_send_operations WHERE created_at >= :startOfDay")
    suspend fun getCountSince(startOfDay: Long): Int

    @Query("SELECT COUNT(*) FROM sms_send_operations WHERE (state = 'SENT' OR state = 'DELIVERED') AND created_at >= :startOfDay")
    suspend fun getSuccessCountSince(startOfDay: Long): Int

    @Query("SELECT COUNT(*) FROM sms_send_operations WHERE state = 'FAILED' AND created_at >= :startOfDay")
    suspend fun getFailedCountSince(startOfDay: Long): Int

    @Query("SELECT COUNT(*) FROM sms_send_operations WHERE state = 'UNKNOWN_AFTER_SUBMIT' AND created_at >= :startOfDay")
    suspend fun getUnknownCountSince(startOfDay: Long): Int

    @Query("SELECT * FROM sms_send_operations ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentOperations(limit: Int = 50): List<SmsSendOperationEntity>

    @Query("DELETE FROM sms_send_operations WHERE created_at < :cutoff")
    suspend fun deleteExpired(cutoff: Long)

    @Transaction
    suspend fun createOperationWithParts(operation: SmsSendOperationEntity, parts: List<SmsSendPartEntity>) {
        insertOperation(operation)
        for (part in parts) {
            insertPart(part)
        }
    }
}
