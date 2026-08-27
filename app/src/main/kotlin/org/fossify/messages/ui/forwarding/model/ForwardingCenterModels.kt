package org.fossify.messages.ui.forwarding.model

import org.fossify.messages.models.ForwardingShadowAttempt
import org.fossify.messages.models.ForwardingShadowDelivery

/**
 * 转发通道健康度模型
 */
data class ChannelHealth(
    val channel: String,
    val totalDeliveries: Int,
    val successCount: Int,
    val failureCount: Int,
    val successRate: Double,
    val lastError: String? = null,
    val lastActiveTime: Long? = null
)

/**
 * 转发中心聚合状态模型
 */
data class ForwardingCenterState(
    val channelHealthList: List<ChannelHealth> = emptyList(),
    val recentDeliveries: List<ForwardingShadowDelivery> = emptyList(),
    val recentAttempts: List<ForwardingShadowAttempt> = emptyList(),
    val pendingOutboxCount: Int = 0,
    val retryOutboxCount: Int = 0,
    val failedOutboxCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
