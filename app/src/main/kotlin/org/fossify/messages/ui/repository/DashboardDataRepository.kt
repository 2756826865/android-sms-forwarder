package org.fossify.messages.ui.repository

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.compatibility.CompatibilityManager
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.forwarding.ForwardingHistoryStore
import org.fossify.messages.models.ForwardingShadowDelivery
import org.fossify.messages.models.RecoveryRecordEntity
import org.fossify.messages.models.RemoteCommandExecutionEntity
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.ui.dashboard.model.DashboardStats
import java.util.Calendar

class DashboardDataRepository(private val context: Context) {

    suspend fun getDashboardStats(): DashboardStats = withContext(Dispatchers.IO) {
        val db = context.getMessagesDB()
        val failures = linkedSetOf<String>()
        suspend fun count(section: String, query: suspend () -> Int): Int = try {
            query()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { failures.add(section); -1 }

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis
        val receivedCount = count("短信接收统计") { db.MessagesDao().getReceivedSmsCountBetween(startOfDay / 1000, endOfDay / 1000) }

        // 1. 短信概览
        val sentCount = count("短信发送统计") { db.SmsSendDao().getCountSince(startOfDay) }
        val successSendCount = count("短信发送统计") { db.SmsSendDao().getSuccessCountSince(startOfDay) }
        val failedSendCount = count("短信发送统计") { db.SmsSendDao().getFailedCountSince(startOfDay) }
        val unknownSendCount = count("短信发送统计") { db.SmsSendDao().getUnknownCountSince(startOfDay) }

        // Use the same actual lifecycle records as the history list. Shadow observation
        // may be disabled or incomplete and must not be presented as business totals.
        val historyStore = ForwardingHistoryStore(context)
        val counts = try { historyStore.countsBetween(startOfDay, endOfDay) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { failures.add("转发统计"); null }
        val forwardSuccessCount = counts?.get(ForwardingHistoryStore.STATUS_SUCCESS) ?: if (counts == null) -1 else 0
        val forwardFailedCount = counts?.get(ForwardingHistoryStore.STATUS_FAILED) ?: if (counts == null) -1 else 0
        val forwardPendingCount = if (counts == null) -1 else listOf(
            ForwardingHistoryStore.STATUS_QUEUED, ForwardingHistoryStore.STATUS_RUNNING,
            ForwardingHistoryStore.STATUS_RETRY, ForwardingHistoryStore.STATUS_WAITING_NETWORK
        ).sumOf { counts[it] ?: 0 }

        // 3. Outbox 健康
        val pendingOutboxCount = count("待发队列统计") { db.OutboxTaskDao().getPendingTaskCount() }
        val retryOutboxCount = count("待发队列统计") { db.OutboxTaskDao().getRetryTaskCount() }
        val failedOutboxCount = count("待发队列统计") { db.OutboxTaskDao().getFailedTaskCount() }

        // 4. Recovery 健康
        val recoveryRecords = try { db.RecoveryRecordDao().queryLatest(100) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { failures.add("恢复记录"); emptyList() }
        val todayRecoveryCount = count("恢复统计") { db.RecoveryRecordDao().getCountSince(startOfDay) }
        val latestRecovery = recoveryRecords.firstOrNull()

        // 5. OEM 状态
        val deviceProfile = CompatibilityManager.deviceProfile
        val isBatteryOptimized = !CompatibilityManager.backgroundCompat.isBatteryOptimizationIgnored(context)
        val isNotificationEnabled = CompatibilityManager.backgroundCompat.isNotificationEnabled(context)
        val isDefaultSms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }
        val brandTips = CompatibilityManager.backgroundCompat.getBrandTips()

        // 6. 发送与转发流水记录 (最新 30 条)
        val historyRecords = try { historyStore.records(limit = 30) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { failures.add("转发历史"); emptyList() }

        DashboardStats(
            todayReceivedCount = receivedCount,
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
            totalRecoveryEvents = count("恢复总数") { db.RecoveryRecordDao().getTotalCount() },
            collectionFailures = failures.toSet(),
            todayRecoveryCount = todayRecoveryCount,
            lastRecoveryTime = latestRecovery?.scanTime,
            lastRecoveryAction = latestRecovery?.actionTaken,
            deviceProfile = deviceProfile,
            isBatteryOptimized = isBatteryOptimized,
            isNotificationEnabled = isNotificationEnabled,
            isDefaultSmsApp = isDefaultSms,
            brandTips = brandTips,
            recentHistoryRecords = historyRecords,
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
