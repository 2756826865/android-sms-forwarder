package org.fossify.messages.outbox

import android.content.Context
import org.fossify.messages.models.OutboxTaskEntity

/**
 * Outbox 任务执行结果
 */
sealed class OutboxExecutionResult {
    /** 执行成功并归档 */
    object Success : OutboxExecutionResult()

    /** 可重试失败 (将根据退避策略重新排期) */
    data class Retry(val errorClass: String?, val errorMessage: String?) : OutboxExecutionResult()

    /** 致命硬错误 (直接标记为死信 FAILED，不再重试) */
    data class FatalFailure(val errorClass: String?, val errorMessage: String?) : OutboxExecutionResult()
}

/**
 * 抽象 Outbox 任务执行器
 */
interface OutboxExecutor {
    /** 判断是否支持执行此类型的任务 */
    fun canExecute(taskType: String): Boolean

    /** 执行任务逻辑并返回结果 */
    suspend fun execute(context: Context, task: OutboxTaskEntity): OutboxExecutionResult
}

/**
 * Outbox 执行器注册中心
 */
object OutboxExecutorRegistry {
    private val executors = mutableListOf<OutboxExecutor>()

    fun register(executor: OutboxExecutor) {
        synchronized(executors) {
            if (!executors.contains(executor)) {
                executors.add(executor)
            }
        }
    }

    fun unregister(executor: OutboxExecutor) {
        synchronized(executors) {
            executors.remove(executor)
        }
    }

    fun findExecutor(taskType: String): OutboxExecutor? = synchronized(executors) {
        executors.find { it.canExecute(taskType) }
    }

    fun clear() {
        synchronized(executors) {
            executors.clear()
        }
    }
}
