package org.fossify.messages.outbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.OutboxRepository
import org.fossify.messages.models.OutboxTaskEntity
import java.util.UUID

/**
 * Outbox 调度器
 *
 * 职责：
 * 1. 扫描就绪的 PENDING / RETRY_WAITING 任务；
 * 2. 借助乐观软锁原子领用任务 (状态转为 RUNNING)；
 * 3. 匹配注册的 OutboxExecutor 并分发执行；
 * 4. 根据执行结果回写 SUCCESS / RETRY_WAITING / FAILED 状态。
 */
object OutboxDispatcher {
    private const val TAG = "OutboxDispatcher"
    private const val DEFAULT_BATCH_SIZE = 10
    private const val DEFAULT_LOCK_DURATION_MS = 60_000L // 60s

    data class DispatchSummary(
        val totalFound: Int,
        val claimedCount: Int,
        val successCount: Int,
        val retryCount: Int,
        val fatalCount: Int,
        val skippedCount: Int
    )

    /**
     * 执行单轮调度扫描与任务处理
     */
    suspend fun dispatchOnce(
        context: Context,
        workerId: String = "dispatcher-" + UUID.randomUUID().toString().take(8),
        limit: Int = DEFAULT_BATCH_SIZE
    ): DispatchSummary = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dao = context.getMessagesDB().OutboxTaskDao()
        val pendingTasks = try {
            dao.findPendingTasks(now = now, limit = limit)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to find pending outbox tasks: ${e.message}")
            return@withContext DispatchSummary(0, 0, 0, 0, 0, 0)
        }

        var claimedCount = 0
        var successCount = 0
        var retryCount = 0
        var fatalCount = 0
        var skippedCount = 0

        for (task in pendingTasks) {
            val claimed = OutboxRepository.claimTask(
                context = context,
                taskId = task.taskId,
                workerId = workerId,
                lockDurationMs = DEFAULT_LOCK_DURATION_MS
            )

            if (!claimed) {
                skippedCount++
                continue
            }
            claimedCount++

            val executor = OutboxExecutorRegistry.findExecutor(task.taskType)
            if (executor == null) {
                Log.w(TAG, "No executor found for task type: ${task.taskType}, taskId: ${task.taskId}")
                OutboxRepository.recordFatalFailure(
                    context,
                    task.taskId,
                    errorClass = "NoExecutorRegisteredException",
                    errorMessage = "No executor found for taskType=${task.taskType}"
                )
                fatalCount++
                continue
            }

            try {
                when (val result = executor.execute(context, task)) {
                    is OutboxExecutionResult.Success -> {
                        OutboxRepository.recordSuccess(context, task.taskId)
                        successCount++
                    }
                    is OutboxExecutionResult.Retry -> {
                        OutboxRepository.recordFailure(
                            context,
                            task.taskId,
                            errorClass = result.errorClass,
                            errorMessage = result.errorMessage
                        )
                        retryCount++
                    }
                    is OutboxExecutionResult.FatalFailure -> {
                        OutboxRepository.recordFatalFailure(
                            context,
                            task.taskId,
                            errorClass = result.errorClass,
                            errorMessage = result.errorMessage
                        )
                        fatalCount++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unhandled exception during task execution ${task.taskId}: ${e.message}")
                OutboxRepository.recordFailure(
                    context,
                    task.taskId,
                    errorClass = e.javaClass.name,
                    errorMessage = e.message
                )
                retryCount++
            }
        }

        DispatchSummary(
            totalFound = pendingTasks.size,
            claimedCount = claimedCount,
            successCount = successCount,
            retryCount = retryCount,
            fatalCount = fatalCount,
            skippedCount = skippedCount
        )
    }
}
