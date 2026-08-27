package org.fossify.messages.ui.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.compatibility.CompatibilityManager
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.models.ForwardingShadowDelivery
import org.fossify.messages.models.RecoveryRecordEntity
import org.fossify.messages.models.RemoteCommandExecutionEntity
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.ui.dashboard.model.DashboardStats
import java.util.Calendar

class DashboardDataRepository(private val context: Context) {

    suspend fun getDashboardStats(): DashboardStats = withContext(Dispatchers.IO) {
        val db = context.getMessagesDB()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis

        // 1. 短信概览
        val sentCount = runCatching { db.SmsSendDao().getCountSince(startOfDay) }.getOrDefault(0)
        val successSendCount = runCatching { db.SmsSendDao().getSuccessCountSince(startOfDay) }.getOrDefault(0)
        val failedSendCount = runCatching { db.SmsSendDao().getFailedCountSince(startOfDay) }.getOrDefault(0)
        val unknownSendCount = runCatching { db.SmsSendDao().getUnknownCountSince(startOfDay) }.getOrDefault(0)

        // 2. 转发概览
        val forwardSuccessCount = runCatching { db.ShadowDaos().getDeliveredCountSince(startOfDay) }.getOrDefault(0)
        val forwardFailedCount = runCatching { db.ShadowDaos().getFailedCountSince(startOfDay) }.getOrDefault(0)
        val forwardPendingCount = runCatching { db.ShadowDaos().getPendingCountSince(startOfDay) }.getOrDefault(0)

        // 3. Outbox 健康
        val pendingOutboxCount = runCatching { db.OutboxTaskDao().getPendingTaskCount() }.getOrDefault(0)
        val retryOutboxCount = runCatching { db.OutboxTaskDao().getRetryTaskCount() }.getOrDefault(0)
        val failedOutboxCount = runCatching { db.OutboxTaskDao().getFailedTaskCount() }.getOrDefault(0)

        // 4. Recovery 健康
        val recoveryRecords = runCatching { db.RecoveryRecordDao().queryLatest(100) }.getOrDefault(emptyList())
        val todayRecoveryCount = runCatching { db.RecoveryRecordDao().getCountSince(startOfDay) }.getOrDefault(0)
        val latestRecovery = recoveryRecords.firstOrNull()

        // 5. OEM 状态
        val deviceProfile = CompatibilityManager.deviceProfile
        val isBatteryOptimized = !CompatibilityManager.backgroundCompat.isBatteryOptimizationIgnored(context)
        val isNotificationEnabled = CompatibilityManager.backgroundCompat.isNotificationEnabled(context)
        val brandTips = CompatibilityManager.backgroundCompat.getBrandTips()

        DashboardStats(
            todaySentCount = sentCount,
            todaySuccessCount = successSendCount,
            todayFailedSendCount = failedSendCount,
            todayUnknownCount = unknownSendCount,
            todayForwardSuccessCount = forwardSuccessCount,
            todayForwardFailedCount = forwardFailedCount,
            todayForwardPendingCount = forwardPendingCount,
            pendingOutboxCount = pendingOutboxCount,
            retryOutboxCount = retryOutboxCount,
            failedOutboxCount = failedOutboxCount,
            totalRecoveryEvents = recoveryRecords.size,
            todayRecoveryCount = todayRecoveryCount,
            lastRecoveryTime = latestRecovery?.scanTime,
            lastRecoveryAction = latestRecovery?.actionTaken,
            deviceProfile = deviceProfile,
            isBatteryOptimized = isBatteryOptimized,
            isNotificationEnabled = isNotificationEnabled,
            brandTips = brandTips,
            lastUpdated = System.currentTimeMillis()
        )
    }

    suspend fun getRecentRecoveryRecords(limit: Int = 50): List<RecoveryRecordEntity> = withContext(Dispatchers.IO) {
        runCatching { context.getMessagesDB().RecoveryRecordDao().queryLatest(limit) }.getOrDefault(emptyList())
    }

    suspend fun getRecentRemoteCommands(limit: Int = 50): List<RemoteCommandExecutionEntity> = withContext(Dispatchers.IO) {
        runCatching { context.getMessagesDB().RemoteCommandDao().getRecentCommands(limit) }.getOrDefault(emptyList())
    }

    suspend fun getRecentDeliveries(limit: Int = 50): List<ForwardingShadowDelivery> = withContext(Dispatchers.IO) {
        runCatching { context.getMessagesDB().ShadowDaos().getRecentDeliveries(limit) }.getOrDefault(emptyList())
    }

    suspend fun getRecentSendOperations(limit: Int = 50): List<SmsSendOperationEntity> = withContext(Dispatchers.IO) {
        runCatching { context.getMessagesDB().SmsSendDao().getRecentOperations(limit) }.getOrDefault(emptyList())
    }
}
