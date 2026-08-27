package org.fossify.messages.ui.messages.model

import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.models.SmsSendPartEntity

/**
 * 消息中心列表项数据模型
 */
data class MessageHistoryItem(
    val operationId: String,
    val triggerType: String,
    val state: String,
    val addressHmac: String?,
    val bodyLength: Int,
    val subscriptionId: Int,
    val partsCount: Int,
    val partsDeliveredCount: Int,
    val parts: List<SmsSendPartEntity> = emptyList(),
    val errorClass: String? = null,
    val submittedAt: Long? = null,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 消息时间线节点
 */
data class MessageTimelineStage(
    val stageName: String,
    val timestamp: Long?,
    val status: String,
    val details: String? = null
)

/**
 * 单条短信完整生命周期详情
 */
data class MessageDetailTimeline(
    val operationId: String,
    val operation: SmsSendOperationEntity,
    val parts: List<SmsSendPartEntity>,
    val stages: List<MessageTimelineStage>
)
