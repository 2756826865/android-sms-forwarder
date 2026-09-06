package org.fossify.messages.models

/**
 * 远程指令状态枚举
 */
enum class RemoteCommandState {
    /** 收到远程指令，完成首次持久化 */
    RECEIVED,
    /** 授权/白名单/规则校验通过，允许执行 */
    AUTHORIZED,
    /** 授权失败、未在白名单或被规则阻止 */
    REJECTED,
    /** 命中永久幂等键，拒绝重复执行 */
    DUPLICATE,
    /** 已排入执行队列 (WorkManager) */
    QUEUED,
    /** 执行链路开始准备提交发信 */
    SUBMITTING,
    /** 短信已写入系统 Outbox/Sent 并通过 SmsManager.sendMultipartTextMessage 提交 */
    SUBMITTED,
    /** 系统广播 SMS_SENT_ACTION 确认底层无线电网络已成功送出 */
    SENT,
    /** 系统广播 SMS_DELIVERED_ACTION 确认目标终端已成功接收送达 */
    DELIVERED,
    /** 执行过程中发生不可恢复错误或底层发信失败 */
    FAILED,
    /** 状态未知 */
    UNKNOWN,
    /** 历史兼容成功状态 (等同于 SENT) */
    SUCCESS,
    /** 执行链路正在运行中 */
    RUNNING
}

/**
 * 远程指令来源类型常量
 */
object RemoteCommandSourceType {
    const val SMS = "SMS"
    const val DINGTALK = "DINGTALK"
    const val FEISHU = "FEISHU"
    const val WECOM = "WECOM"
    const val EMAIL = "EMAIL"
    const val TELEGRAM = "TELEGRAM"
    const val WEBSOCKET = "WEBSOCKET"
    const val QQ = "QQ"
    const val HTTP_API = "HTTP_API"
}

/**
 * 远程指令动作类型常量
 */
object RemoteCommandType {
    const val SEND_SMS = "SEND_SMS"
    const val QUERY_STATUS = "QUERY_STATUS"
    const val UNKNOWN = "UNKNOWN"
}

/**
 * 远程指令上下文（进入持久化时的参数载荷）
 */
data class RemoteCommandContext(
    val sourceType: String,
    val sourceMessageKey: String,
    val commandType: String = RemoteCommandType.SEND_SMS,
    val rawTarget: String?,
    val rawPayload: String,
    val requestedSimMode: Int,
    val rawRequester: String,
    val receivedAt: Long = System.currentTimeMillis()
)
