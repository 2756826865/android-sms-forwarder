package org.fossify.messages.models

/**
 * 恢复扫描触发源
 */
object RecoveryTriggerSource {
    const val STARTUP = "RECOVERY_STARTUP"
    const val PERIODIC_WORKER = "PERIODIC_WORKER"
    const val MANUAL = "MANUAL"
}

/**
 * 恢复处置动作
 */
object RecoveryAction {
    const val UNLOCK_TASK = "UNLOCK_TASK"
    const val RESET_PENDING = "RESET_PENDING"
    const val MARK_UNKNOWN = "MARK_UNKNOWN"
    const val ALIGN_STATE = "ALIGN_STATE"
    const val SYNC_SUCCESS = "SYNC_SUCCESS"
    const val SYNC_FAILED = "SYNC_FAILED"
    const val MARK_FAILED = "MARK_FAILED"
}

/**
 * 恢复扫描目标对象类型
 */
object RecoveryObjectType {
    const val OUTBOX_TASK = "OUTBOX_TASK"
    const val SMS_OPERATION = "SMS_OPERATION"
    const val FORWARDING_DELIVERY = "FORWARDING_DELIVERY"
    const val REMOTE_COMMAND = "REMOTE_COMMAND"
}

/**
 * 恢复扫描结果汇总
 */
data class RecoverySummary(
    val triggerSource: String,
    val scanTime: Long = System.currentTimeMillis(),
    val totalScanned: Int,
    val totalRecovered: Int,
    val unlockedTasks: Int,
    val resetPendingTasks: Int,
    val markedUnknownCount: Int
)
