package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import org.fossify.messages.models.OutboxTaskEntity

@Dao
interface OutboxTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OutboxTaskEntity): Long

    @Update
    suspend fun update(entity: OutboxTaskEntity)

    @Query("SELECT * FROM outbox_tasks WHERE task_id = :taskId")
    suspend fun findById(taskId: String): OutboxTaskEntity?

    @Query("SELECT * FROM outbox_tasks WHERE source_type = :sourceType AND source_id = :sourceId ORDER BY created_at DESC")
    suspend fun findBySource(sourceType: String, sourceId: String): List<OutboxTaskEntity>

    @Query("SELECT * FROM outbox_tasks WHERE (state = 'PENDING' OR (state = 'RETRY_WAITING' AND next_retry_at <= :now)) AND (lock_expires_at IS NULL OR lock_expires_at < :now) ORDER BY next_retry_at ASC, created_at ASC LIMIT :limit")
    suspend fun findPendingTasks(now: Long = System.currentTimeMillis(), limit: Int = 20): List<OutboxTaskEntity>

    @Query("SELECT * FROM outbox_tasks WHERE state = 'RUNNING' AND lock_expires_at IS NOT NULL AND lock_expires_at < :now")
    suspend fun findDeadlockedTasks(now: Long = System.currentTimeMillis()): List<OutboxTaskEntity>

    @Query("SELECT * FROM outbox_tasks WHERE state = 'RETRY_WAITING' AND next_retry_at <= :now")
    suspend fun findDueRetryTasks(now: Long = System.currentTimeMillis()): List<OutboxTaskEntity>

    @Query("SELECT * FROM outbox_tasks WHERE state = 'RUNNING'")
    suspend fun findRunningTasks(): List<OutboxTaskEntity>

    @Query("SELECT COUNT(*) FROM outbox_tasks WHERE state = 'PENDING'")
    suspend fun getPendingTaskCount(): Int

    @Query("SELECT COUNT(*) FROM outbox_tasks WHERE state = 'RETRY_WAITING'")
    suspend fun getRetryTaskCount(): Int

    @Query("SELECT COUNT(*) FROM outbox_tasks WHERE state = 'FAILED'")
    suspend fun getFailedTaskCount(): Int

    @Query("UPDATE outbox_tasks SET state = 'RUNNING', locked_by = :workerId, lock_expires_at = :lockExpiresAt, updated_at = :now WHERE task_id = :taskId AND (lock_expires_at IS NULL OR lock_expires_at < :now)")
    suspend fun claimTask(taskId: String, workerId: String, lockExpiresAt: Long, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE outbox_tasks SET state = :state, updated_at = :now WHERE task_id = :taskId")
    suspend fun updateState(taskId: String, state: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE outbox_tasks SET state = 'SUCCESS', locked_by = NULL, lock_expires_at = NULL, updated_at = :now WHERE task_id = :taskId")
    suspend fun markSuccess(taskId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE outbox_tasks SET state = 'FAILED', last_error_class = :errorClass, last_error_hmac = :errorHmac, locked_by = NULL, lock_expires_at = NULL, updated_at = :now WHERE task_id = :taskId")
    suspend fun markFailed(taskId: String, errorClass: String?, errorHmac: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE outbox_tasks SET state = 'RETRY_WAITING', attempt_count = :attemptCount, next_retry_at = :nextRetryAt, last_error_class = :errorClass, last_error_hmac = :errorHmac, locked_by = NULL, lock_expires_at = NULL, updated_at = :now WHERE task_id = :taskId")
    suspend fun scheduleRetry(
        taskId: String,
        nextRetryAt: Long,
        attemptCount: Int,
        errorClass: String?,
        errorHmac: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE outbox_tasks SET locked_by = NULL, lock_expires_at = NULL, updated_at = :now WHERE task_id = :taskId")
    suspend fun releaseLock(taskId: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM outbox_tasks WHERE created_at < :cutoff")
    suspend fun deleteExpired(cutoff: Long)
}
