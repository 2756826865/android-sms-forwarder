package org.fossify.messages.ui.forwarding.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.models.ForwardingShadowDelivery
import org.fossify.messages.ui.forwarding.model.ChannelHealth
import org.fossify.messages.ui.forwarding.model.ForwardingCenterState

/**
 * 转发中心只读数据仓库
 */
class ForwardingCenterRepository(private val context: Context) {

    suspend fun getForwardingCenterState(): ForwardingCenterState = withContext(Dispatchers.IO) {
        val shadowDao = context.getMessagesDB().ShadowDaos()
        val outboxDao = context.getMessagesDB().OutboxTaskDao()

        val channels = runCatching { shadowDao.getAllChannels() }.getOrDefault(emptyList())
        val channelHealthList = channels.map { channel ->
            val deliveries = shadowDao.getDeliveriesForChannel(channel, 100)
            val succ = deliveries.count { it.state == "DELIVERED" || it.state == "SUCCESS" }
            val fail = deliveries.count { it.state == "FAILED" }
            val total = deliveries.size
            val rate = if (total > 0) (succ.toDouble() / total) * 100.0 else 0.0
            val lastActive = deliveries.firstOrNull()?.updatedAt

            ChannelHealth(
                channel = channel,
                totalDeliveries = total,
                successCount = succ,
                failureCount = fail,
                successRate = rate,
                lastError = if (fail > 0) "Has failed deliveries" else null,
                lastActiveTime = lastActive
            )
        }

        val recentDeliveries = runCatching { shadowDao.getRecentDeliveries(50) }.getOrDefault(emptyList())
        val recentAttempts = runCatching { shadowDao.getRecentAttempts(50) }.getOrDefault(emptyList())
        val pendingOutbox = runCatching { outboxDao.getPendingTaskCount() }.getOrDefault(0)
        val retryOutbox = runCatching { outboxDao.getRetryTaskCount() }.getOrDefault(0)
        val failedOutbox = runCatching { outboxDao.getFailedTaskCount() }.getOrDefault(0)

        ForwardingCenterState(
            channelHealthList = channelHealthList,
            recentDeliveries = recentDeliveries,
            recentAttempts = recentAttempts,
            pendingOutboxCount = pendingOutbox,
            retryOutboxCount = retryOutbox,
            failedOutboxCount = failedOutbox,
            lastUpdated = System.currentTimeMillis()
        )
    }

    suspend fun getRecentDeliveries(limit: Int = 50): List<ForwardingShadowDelivery> = withContext(Dispatchers.IO) {
        runCatching { context.getMessagesDB().ShadowDaos().getRecentDeliveries(limit) }.getOrDefault(emptyList())
    }
}
