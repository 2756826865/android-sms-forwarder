package org.fossify.messages.forwarding

import android.content.Context
import java.security.MessageDigest

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
        val key = MessageDigest.getInstance("SHA-256").digest(recordId.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
        )
    }
}
