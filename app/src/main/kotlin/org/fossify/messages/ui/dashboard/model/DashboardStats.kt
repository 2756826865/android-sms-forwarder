package org.fossify.messages.ui.dashboard.model

import org.fossify.messages.compatibility.model.DeviceProfile
import org.fossify.messages.forwarding.ForwardingHistoryRecord

/**
 * 首页仪表盘统计聚合数据模型
 */
data class DashboardStats(
    // 1. 短信概览卡片
    val todaySentCount: Int = 0,
    val todaySuccessCount: Int = 0,
    val todayFailedSendCount: Int = 0,
    val todayUnknownCount: Int = 0,

    // 2. 转发概览卡片
    val todayForwardSuccessCount: Int = 0,
    val todayForwardFailedCount: Int = 0,
    val todayForwardPendingCount: Int = 0,

    // 3. Outbox 健康卡片
    val pendingOutboxCount: Int = 0,
    val retryOutboxCount: Int = 0,
    val failedOutboxCount: Int = 0,

    // 4. Recovery 健康卡片
    val totalRecoveryEvents: Int = 0,
    val todayRecoveryCount: Int = 0,
    val lastRecoveryTime: Long? = null,
    val lastRecoveryAction: String? = null,

    // 5. OEM 状态卡片
    val deviceProfile: DeviceProfile? = null,
    val isBatteryOptimized: Boolean = false,
    val isNotificationEnabled: Boolean = true,
    val isDefaultSmsApp: Boolean = true,
    val brandTips: List<String> = emptyList(),

    // 6. 发送与转发流水记录 (最新 30 条)
    val recentHistoryRecords: List<ForwardingHistoryRecord> = emptyList(),

    val lastUpdated: Long = System.currentTimeMillis()
)
