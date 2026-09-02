package org.fossify.messages.remote

import android.app.Activity
import android.content.Context
import android.net.Uri
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.forwarding.PushPlusWorker
import org.fossify.messages.receivers.SendStatusReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RemoteControlReceiptConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var includeDelivered: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_DELIVERED, false)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_DELIVERED, value).apply()

    var channels: Set<String>
        get() = prefs.getString(KEY_CHANNELS, "[]").orEmpty().let(::decodeChannels)
        set(value) = prefs.edit().putString(KEY_CHANNELS, encodeChannels(value)).apply()

    private fun encodeChannels(channels: Set<String>): String = JSONArray(channels.toList()).toString()

    private fun decodeChannels(raw: String): Set<String> = runCatching {
        val array = JSONArray(raw)
        buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.getOrDefault(emptySet())

    companion object {
        private const val PREFS_NAME = "remote_control_receipt"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INCLUDE_DELIVERED = "include_delivered"
        private const val KEY_CHANNELS = "channels"
    }
}

data class RemoteControlPendingReceipt(
    val target: String,
    val content: String,
    val source: String,
    val requester: String,
    val awaitDelivered: Boolean,
    val sendSimLabel: String = "",
    val commandId: String = "",
)

object RemoteSmsReceiptTracker {
    private const val PREFS_NAME = "remote_control_receipt_pending"
    private const val KEY_PREFIX = "pending_"
    private const val MAX_PENDING = 30

    fun register(context: Context, messageId: Long, receipt: RemoteControlPendingReceipt) {
        if (messageId <= 0L) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        trimOldEntries(prefs)
        prefs.edit().putString(key(messageId), encodeReceipt(receipt)).apply()
    }

    fun get(context: Context, messageId: Long): RemoteControlPendingReceipt? {
        if (messageId <= 0L) return null
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(messageId), null)
            ?.let(::decodeReceipt)
    }

    fun remove(context: Context, messageId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(messageId))
            .apply()
    }

    private fun key(messageId: Long) = KEY_PREFIX + messageId

    private fun trimOldEntries(prefs: android.content.SharedPreferences) {
        val keys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }
        if (keys.size <= MAX_PENDING) return
        keys.drop(MAX_PENDING).forEach { prefs.edit().remove(it).apply() }
    }

    private fun encodeReceipt(receipt: RemoteControlPendingReceipt): String = JSONObject()
        .put("target", receipt.target)
        .put("content", receipt.content)
        .put("source", receipt.source)
        .put("requester", receipt.requester)
        .put("awaitDelivered", receipt.awaitDelivered)
        .put("sendSimLabel", receipt.sendSimLabel)
        .put("commandId", receipt.commandId)
        .toString()

    private fun decodeReceipt(raw: String): RemoteControlPendingReceipt? = runCatching {
        val json = JSONObject(raw)
        RemoteControlPendingReceipt(
            target = json.optString("target"),
            content = json.optString("content"),
            source = json.optString("source"),
            requester = json.optString("requester"),
            awaitDelivered = json.optBoolean("awaitDelivered"),
            sendSimLabel = json.optString("sendSimLabel"),
            commandId = json.optString("commandId"),
        )
    }.getOrNull()
}

object RemoteControlReceiptForwarder {
    fun registerFromMessageUris(
        context: Context,
        uris: List<Uri>,
        receipt: RemoteControlPendingReceipt,
    ) {
        val config = RemoteControlReceiptConfig(context)
        if (!config.enabled) return
        uris.forEach { uri ->
            uri.lastPathSegment?.toLongOrNull()?.let { messageId ->
                RemoteSmsReceiptTracker.register(
                    context,
                    messageId,
                    receipt.copy(awaitDelivered = config.includeDelivered),
                )
            }
        }
    }

    fun onSendResult(context: Context, messageId: Long, resultCode: Int, errorCode: Int = SendStatusReceiver.NO_ERROR_CODE) {
        val pending = RemoteSmsReceiptTracker.get(context, messageId) ?: return
        val success = resultCode == Activity.RESULT_OK
        val status = if (success) {
            if (pending.awaitDelivered) "发送成功，等待送达报告" else "发送成功"
        } else {
            val detail = if (errorCode != SendStatusReceiver.NO_ERROR_CODE) "（错误码 $errorCode）" else ""
            "发送失败$detail"
        }
        forward(context, status, pending)
        if (!success || !pending.awaitDelivered) {
            RemoteSmsReceiptTracker.remove(context, messageId)
        }
    }

    fun onDelivered(context: Context, messageId: Long, delivered: Boolean) {
        val pending = RemoteSmsReceiptTracker.get(context, messageId) ?: return
        forward(context, if (delivered) "已送达" else "送达失败或未确认", pending)
        RemoteSmsReceiptTracker.remove(context, messageId)
    }

    fun forwardImmediate(context: Context, status: String, pending: RemoteControlPendingReceipt) {
        forward(context, status, pending)
    }

    private fun forward(context: Context, status: String, pending: RemoteControlPendingReceipt) {
        val config = RemoteControlReceiptConfig(context)
        if (!config.enabled) return
        val channels = config.channels.intersect(ForwardingChannels.allRuleChannels.toSet())
        if (channels.isEmpty()) return

        val now = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val title = "远程指令回执 · $status"
        val body = buildString {
            appendLine("状态：$status")
            appendLine("来源：${pending.source}")
            if (pending.requester.isNotBlank()) appendLine("触发方：${pending.requester}")
            if (pending.sendSimLabel.isNotBlank()) appendLine("发送卡：${pending.sendSimLabel}")
            appendLine("目标号码：${pending.target}")
            appendLine("发送内容：${pending.content}")
            append("时间：$now")
        }
        val uniqueId = "remote-receipt-$now-${pending.target.hashCode()}"

        val receiptSimSuffix = pending.sendSimLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        RemoteSmsCommandConfig(context).appendLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
        val multiConfig = MultiForwardConfig(context)
        when (pending.source) {
            SOURCE_DINGTALK -> multiConfig.appendDingTalkRemoteLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
            SOURCE_FEISHU -> multiConfig.appendFeishuRemoteLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
            SOURCE_WECOM -> multiConfig.appendWeComRemoteLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
            SOURCE_EMAIL -> multiConfig.appendEmailRemoteLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
            SOURCE_TELEGRAM -> multiConfig.appendTelegramRemoteLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
            SOURCE_WEBSOCKET -> multiConfig.appendWebSocketRemoteLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
            SOURCE_QQ -> multiConfig.appendQqRemoteLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
        }

        if (ForwardingChannels.PUSHPLUS in channels && PushPlusConfig(context).enabled) {
            PushPlusWorker.enqueue(
                context = context,
                sender = title,
                body = body,
                receivedAt = System.currentTimeMillis(),
                subscriptionId = -1,
                uniqueId = uniqueId,
            )
        }
        val multiChannels = channels - ForwardingChannels.PUSHPLUS
        if (multiChannels.isNotEmpty()) {
            MultiChannelForwardWorker.enqueue(
                context = context,
                sender = title,
                body = body,
                receivedAt = System.currentTimeMillis(),
                subscriptionId = -1,
                uniqueId = uniqueId,
                allowedChannels = multiChannels,
            )
        }

        // 远程渠道原路直连回执
        when (pending.source) {
            SOURCE_TELEGRAM -> TelegramRemotePoller.sendReply(context, pending.requester, "【短信远程指令回执】\n$body")
            SOURCE_WEBSOCKET -> WebSocketRemoteClient.sendReceiptOverSocket(pending.commandId, status, body)
            SOURCE_QQ -> QqRemoteClient.sendReply(pending.requester, "【短信远程指令回执】\n$body")
        }
    }
}
