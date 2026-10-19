package org.fossify.messages.forwarding

import android.content.Context
import java.security.MessageDigest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Replays only the original target, never re-matches rules or restarts a channel group. */
object ForwardingRetry {
    fun enqueue(context: Context, recordId: String) {
        val store = ForwardingHistoryStore(context)
        val record = store.record(recordId) ?: error("记录已不存在")
        require(record.status in setOf(ForwardingHistoryStore.STATUS_FAILED, ForwardingHistoryStore.STATUS_UNKNOWN)) {
            "仅失败或结果未知的记录可手动重试"
        }
        val payload = store.retryPayload(recordId) ?: error("旧日志未保存完整投递数据，不能用截断正文重发")
        val channel = payload.getString("channel")
        require(channel != ForwardingChannels.CHANNEL_GROUP) { "请重试组内具体通道" }
        val config = MultiForwardConfig(context)
        val currentInstance = payload.optString("instanceId").takeIf(String::isNotBlank)
            ?.let(config::getChannelInstanceById)
        if (payload.optString("instanceId").isNotBlank()) {
            require(currentInstance != null && currentInstance.enabled &&
                currentInstance.channelType == channel && currentInstance.hasDispatchConfiguration()) {
                "原通道实例已删除、停用或类型发生变化，未重新提交"
            }
        }
        val includesSmsDirect = channel == ForwardingChannels.SMS_DIRECT ||
            currentInstance?.channelType == ForwardingChannels.SMS_DIRECT ||
            (channel in setOf("", ForwardingChannels.ALL) && ForwardingChannels.SMS_DIRECT in config.enabledChannelIds())
        require(SmsDirectBillingPolicy.canRetryManually(includesSmsDirect, record.attempts)) {
            "此记录已尝试过收费短信发送，不能从日志再次提交；请先核对收件端，确需重发请手动发送短信"
        }
        val key = MessageDigest.getInstance("SHA-256").digest(recordId.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val manager = WorkManager.getInstance(context.applicationContext)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(1L)
            val original = manager.getWorkInfosForUniqueWork(ForwardingQueuePolicy.uniqueName(record.workId, record.channel))
                .get(remaining(), TimeUnit.NANOSECONDS)
            require(ForwardingQueuePolicy.canRetryAfterLookup(original.map { ForwardingWorkState(it.state.name, it.runAttemptCount) })) {
                "原任务仍可能执行、已完成或无法找到，未重新提交；请先查看排队原因并核对接收端"
            }
            val originalRequestId = payload.optString("queueRequestId")
            require(originalRequestId.isBlank() || original.any { it.id.toString() == originalRequestId }) {
                "恢复数据与实际原任务不一致，未重新提交，请先核对投递记录"
            }
            val previousRetry = manager.getWorkInfosForUniqueWork("multi-forward-manual-retry-$key-${record.channel}")
                .get(remaining(), TimeUnit.NANOSECONDS)
            require(previousRetry.isEmpty()) { "此记录已经提交过手动重试，请查看对应重试记录" }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("任务核对已中断，未重新提交", error)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("任务状态查询失败或超时，未重新提交，请稍后再试", error)
        }
        require(store.claimManualRetry(record)) { "此记录状态已改变或已经安排重试，请刷新日志查看；未重复提交" }
        MultiChannelForwardWorker.enqueueSingle(
            context = context, sender = payload.getString("sender"), body = payload.getString("body"),
            receivedAt = payload.getLong("receivedAt"), subscriptionId = payload.getInt("subscriptionId"),
            uniqueId = "manual-retry-$key", targetChannel = channel,
            allowedChannels = setOf(payload.optString("instanceId").ifBlank { channel }),
            isTest = record.isTest, targetInstanceId = payload.optString("instanceId"),
            ruleId = payload.optString("ruleId"), actionId = payload.optString("actionId"),
            bodyAlreadyRendered = payload.optBoolean("bodyAlreadyRendered"), threadId = payload.optLong("threadId"),
            allowSimLabelPrefix = payload.optBoolean("allowSimLabelPrefix", false),
            originalSim = ForwardingSimSnapshot(payload.optInt("slot", -1), payload.optString("sim"),
                payload.optString("receiver"), payload.optString("label")),
            originalSmsDirectSource = payload.optString("smsDirectSource").ifBlank {
                SmsDirectBillingPolicy.workerSource(record.workId, record.receivedAt, record.recordId)
            },
        )
    }
}
