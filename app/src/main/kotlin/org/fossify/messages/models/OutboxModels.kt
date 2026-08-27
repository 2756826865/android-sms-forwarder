package org.fossify.messages.models

/**
 * Outbox 任务状态枚举
 */
enum class OutboxTaskState {
    /** 任务已创建，等待调度 */
    CREATED,
    /** 任务就绪，可被 Dispatcher 领用 */
    PENDING,
    /** 任务正在执行中 (持有软锁) */
    RUNNING,
    /** 任务执行成功并归档 */
    SUCCESS,
    /** 任务执行失败，处于退避重试等待中 */
    RETRY_WAITING,
    /** 任务超过最大重试次数，标记为死信终态 */
    FAILED
}

/**
 * Outbox 任务类型常量
 */
object OutboxTaskType {
    const val SEND_SMS = "SEND_SMS"
    const val FORWARD_HTTP = "FORWARD_HTTP"
    const val FORWARD_SMS = "FORWARD_SMS"
    const val FORWARD_EMAIL = "FORWARD_EMAIL"
    const val FORWARD_PLUGIN = "FORWARD_PLUGIN"
}

/**
 * Outbox 来源类型常量
 */
object OutboxSourceType {
    const val REMOTE_COMMAND = "REMOTE_COMMAND"
    const val FORWARDING_RULE = "FORWARDING_RULE"
    const val BULK_SEND = "BULK_SEND"
    const val SCHEDULED = "SCHEDULED"
}

/**
 * Outbox 任务创建上下文
 */
data class OutboxTaskContext(
    val taskType: String,
    val sourceType: String,
    val sourceId: String,
    val rawPayload: String,
    val payloadPayload: String? = null,
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 0L
)
