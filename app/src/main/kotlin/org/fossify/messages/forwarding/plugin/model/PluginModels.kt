package org.fossify.messages.forwarding.plugin.model

/**
 * 转发通道插件载荷契约
 * 严禁插件持久化明文短信内容，仅允许使用 Hash 与脱敏元数据
 */
data class ForwardPayload(
    val sourceMessageId: String,
    val senderHash: String,
    val bodyHash: String,
    val receivedTime: Long,
    val metadata: Map<String, String> = emptyMap(),
    val targetConfig: Map<String, String> = emptyMap(),
    val rawContentForTransmission: String? = null // 仅用于内存中一次性网络传输，不落地存储
)

/**
 * 转发通道执行结果
 */
sealed class ChannelResult {
    object Success : ChannelResult()
    data class Retry(val reason: String, val retryDelayMs: Long? = null) : ChannelResult()
    data class Failed(val errorClass: String, val errorMessage: String) : ChannelResult()

    val isSuccess: Boolean get() = this is Success
    val isRetry: Boolean get() = this is Retry
    val isFailed: Boolean get() = this is Failed
}
