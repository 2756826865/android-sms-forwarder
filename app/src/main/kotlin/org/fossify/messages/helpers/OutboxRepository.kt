package org.fossify.messages.helpers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.models.OutboxTaskContext
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskState
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

/**
 * Outbox 持久化队列仓库
 *
 * 职责：
 * 1. 业务意图与执行调度的解耦与持久化；
 * 2. 软锁原子领用与释放；
 * 3. 状态机推进与指数退避重试排期；
 * 4. 异步、Fail-Open，严禁直接调用具体的发送/网络执行器。
 */
object OutboxRepository {
    private const val TAG = "OutboxRepository"
    private const val BASE_BACKOFF_MS = 10_000L      // 10s
    private const val MAX_BACKOFF_MS = 10 * 60_000L   // 10min
    private const val DEFAULT_LOCK_DURATION_MS = 60_000L // 60s

    private val outboxScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) + SupervisorJob()
    )

    /**
     * 创建并持久化一个 Outbox 任务，返回 taskId
     */
    suspend fun createTask(context: Context, taskContext: OutboxTaskContext): String = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        try {
            val payloadHmac = ShadowHmacHelper.calculateHmac(taskContext.rawPayload) ?: ""
            val now = System.currentTimeMillis()
            val nextRetryAt = if (taskContext.initialDelayMs > 0L) now + taskContext.initialDelayMs else now

            val entity = OutboxTaskEntity(
                taskId = taskId,
                taskType = taskContext.taskType,
                sourceType = taskContext.sourceType,
                sourceId = taskContext.sourceId,
                payloadHmac = payloadHmac,
                payloadPayload = taskContext.payloadPayload,
                state = OutboxTaskState.PENDING.name,
                attemptCount = 0,
                maxAttempts = taskContext.maxAttempts,
                nextRetryAt = nextRetryAt,
                createdAt = now,
                updatedAt = now
            )
            context.getMessagesDB().OutboxTaskDao().insert(entity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create outbox task $taskId: ${e.message}")
        }
        taskId
    }

    /**
     * 尝试原子领用任务
     */
    suspend fun claimTask(
        context: Context,
        taskId: String,
        workerId: String,
        lockDurationMs: Long = DEFAULT_LOCK_DURATION_MS
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val lockExpiresAt = now + lockDurationMs
            val updatedRows = context.getMessagesDB().OutboxTaskDao().claimTask(taskId, workerId, lockExpiresAt, now)
            updatedRows > 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to claim outbox task $taskId: ${e.message}")
            false
        }
    }

    /**
     * 标记任务执行成功并归档
     */
    fun recordSuccess(context: Context, taskId: String) {
        outboxScope.launch {
            try {
                context.getMessagesDB().OutboxTaskDao().markSuccess(taskId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mark success for task $taskId: ${e.message}")
            }
        }
    }

    /**
     * 记录失败并自动计算指数退避重试或标记死信 FAILED
     */
    fun recordFailure(
        context: Context,
        taskId: String,
        errorClass: String?,
        errorMessage: String? = null
    ) {
        outboxScope.launch {
            try {
                val dao = context.getMessagesDB().OutboxTaskDao()
                val existing = dao.findById(taskId) ?: return@launch
                val newAttemptCount = existing.attemptCount + 1
                val errorHmac = ShadowHmacHelper.calculateHmac(errorMessage)
                val now = System.currentTimeMillis()

                if (newAttemptCount < existing.maxAttempts) {
                    val backoffMultiplier = (1L shl min(newAttemptCount - 1, 6))
                    val delay = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * backoffMultiplier) + Random.nextLong(0, 1000)
                    val nextRetryAt = now + delay
                    dao.scheduleRetry(
                        taskId = taskId,
                        nextRetryAt = nextRetryAt,
                        attemptCount = newAttemptCount,
                        errorClass = errorClass,
                        errorHmac = errorHmac,
                        now = now
                    )
                } else {
                    dao.markFailed(
                        taskId = taskId,
                        errorClass = errorClass,
                        errorHmac = errorHmac,
                        now = now
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record failure for task $taskId: ${e.message}")
            }
        }
    }

    /**
     * 标记致命错误，直接转为死信 FAILED (不重试)
     */
    fun recordFatalFailure(
        context: Context,
        taskId: String,
        errorClass: String?,
        errorMessage: String? = null
    ) {
        outboxScope.launch {
            try {
                val errorHmac = ShadowHmacHelper.calculateHmac(errorMessage)
                context.getMessagesDB().OutboxTaskDao().markFailed(
                    taskId = taskId,
                    errorClass = errorClass,
                    errorHmac = errorHmac,
                    now = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record fatal failure for task $taskId: ${e.message}")
            }
        }
    }

    /**
     * 释放软锁
     */
    fun releaseLock(context: Context, taskId: String) {
        outboxScope.launch {
            try {
                context.getMessagesDB().OutboxTaskDao().releaseLock(taskId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release lock for task $taskId: ${e.message}")
            }
        }
    }
}
