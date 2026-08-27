package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import org.fossify.messages.models.RemoteCommandExecutionEntity

@Dao
interface RemoteCommandDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: RemoteCommandExecutionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: RemoteCommandExecutionEntity)

    @Update
    suspend fun update(entity: RemoteCommandExecutionEntity)

    @Query("SELECT * FROM remote_command_executions WHERE command_id = :commandId")
    suspend fun findById(commandId: String): RemoteCommandExecutionEntity?

    @Query("SELECT * FROM remote_command_executions WHERE source_type = :sourceType AND source_message_key = :sourceMessageKey AND payload_hmac = :payloadHmac LIMIT 1")
    suspend fun findByIdempotencyKey(sourceType: String, sourceMessageKey: String, payloadHmac: String): RemoteCommandExecutionEntity?

    @Query("SELECT * FROM remote_command_executions WHERE execution_state = 'RUNNING'")
    suspend fun findRunningCommands(): List<RemoteCommandExecutionEntity>

    @Query("UPDATE remote_command_executions SET execution_state = :state, updated_at = :now WHERE command_id = :commandId")
    suspend fun updateState(commandId: String, state: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE remote_command_executions SET authorized = :authorized, authorization_reason = :reason, execution_state = :state, updated_at = :now WHERE command_id = :commandId")
    suspend fun recordAuthorization(commandId: String, authorized: Boolean, reason: String, state: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE remote_command_executions SET execution_state = :state, send_operation_id = :sendOperationId, completed_at = :completedAt, error_class = :errorClass, error_hmac = :errorHmac, updated_at = :now WHERE command_id = :commandId")
    suspend fun recordExecutionResult(
        commandId: String,
        state: String,
        sendOperationId: String?,
        completedAt: Long?,
        errorClass: String?,
        errorHmac: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE remote_command_executions SET execution_state = 'DUPLICATE', updated_at = :now WHERE command_id = :commandId")
    suspend fun markDuplicate(commandId: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM remote_command_executions ORDER BY received_at DESC LIMIT :limit")
    suspend fun getRecentCommands(limit: Int = 50): List<RemoteCommandExecutionEntity>

    @Query("DELETE FROM remote_command_executions WHERE created_at < :cutoff")
    suspend fun deleteExpired(cutoff: Long)
}
