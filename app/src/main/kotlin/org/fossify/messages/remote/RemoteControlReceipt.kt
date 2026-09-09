package org.fossify.messages.remote

import android.app.Activity
import android.content.Context
import android.net.Uri
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
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
    val sourceInstanceId: String = "",
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
        val keys = prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .sortedByDescending { it.removePrefix(KEY_PREFIX).toLongOrNull() ?: Long.MIN_VALUE }
        if (keys.size <= MAX_PENDING) return
        prefs.edit().apply {
            keys.drop(MAX_PENDING).forEach(::remove)
        }.apply()
    }

    private fun encodeReceipt(receipt: RemoteControlPendingReceipt): String = JSONObject()
        .put("target", receipt.target)
        .put("content", receipt.content)
        .put("source", receipt.source)
        .put("requester", receipt.requester)
        .put("awaitDelivered", receipt.awaitDelivered)
        .put("sendSimLabel", receipt.sendSimLabel)
        .put("commandId", receipt.commandId)
        .put("sourceInstanceId", receipt.sourceInstanceId)
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
            sourceInstanceId = json.optString("sourceInstanceId")
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
        // 内部状态跟踪永远必须注册，不依赖外部回执开关！
        uris.forEach { uri ->
            uri.lastPathSegment?.toLongOrNull()?.let { messageId ->
                RemoteSmsReceiptTracker.register(
                    context,
                    messageId,
                    receipt.copy(awaitDelivered = config.enabled && config.includeDelivered),
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

        // 真实状态回写闭环：推进 RemoteCommandRepository
        if (pending.commandId.isNotBlank()) {
            if (success) {
                org.fossify.messages.helpers.RemoteCommandRepository.recordSent(context, pending.commandId)
            } else {
                org.fossify.messages.helpers.RemoteCommandRepository.recordExecutionFailure(
                    context = context,
                    commandId = pending.commandId,
                    errorClass = "SmsSendError",
                    errorMessage = "Send failed with resultCode=$resultCode, errorCode=$errorCode"
                )
            }
        }

        forward(context, status, pending)
        if (!success || !pending.awaitDelivered) {
            RemoteSmsReceiptTracker.remove(context, messageId)
        }
    }

    fun onDelivered(context: Context, messageId: Long, delivered: Boolean) {
        val pending = RemoteSmsReceiptTracker.get(context, messageId) ?: return
        if (pending.commandId.isNotBlank()) {
            if (delivered) {
                org.fossify.messages.helpers.RemoteCommandRepository.recordDelivered(context, pending.commandId)
            } else {
                org.fossify.messages.helpers.RemoteCommandRepository.recordExecutionFailure(
                    context = context,
                    commandId = pending.commandId,
                    errorClass = "SmsDeliveryError",
                    errorMessage = "Delivery unconfirmed or failed"
                )
            }
        }
        forward(context, if (delivered) "已送达" else "送达失败或未确认", pending)
        RemoteSmsReceiptTracker.remove(context, messageId)
    }

    fun forwardImmediate(context: Context, status: String, pending: RemoteControlPendingReceipt) {
        forward(context, status, pending)
    }

    private fun forward(context: Context, status: String, pending: RemoteControlPendingReceipt) {
        val config = RemoteControlReceiptConfig(context)
        if (!config.enabled) return
        val channelRepo = org.fossify.messages.forwarding.repository.ChannelRepository.getInstance(context)
        val enabledInstances = channelRepo.getEnabledInstances()
        val targetInstances = if (config.channels.isNotEmpty()) {
            enabledInstances.filter { config.channels.contains(it.id) || config.channels.contains(it.channelType) }
        } else {
            emptyList()
        }

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
            SOURCE_EMAIL -> multiConfig.appendEmailRemoteLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
            SOURCE_TELEGRAM -> multiConfig.appendTelegramRemoteLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
            SOURCE_WEBSOCKET -> multiConfig.appendWebSocketRemoteLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
            SOURCE_WECOM -> multiConfig.appendWeComRemoteLog("回执[$status] -> ${pending.target}$receiptSimSuffix")
        }

        // 1. 所有具备双向会话能力的远程渠道，优先按 sourceInstanceId 原路精准回执。
        val directDelivered = org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager
            .getInstance(context)
            .sendDirectReceipt(pending, status, body)

        // 2. 普通转发通道精准派发（按用户选择的实例分别调用 enqueueSingle）
        // 关键防重优化：如果当前指令来自微信/钉钉/飞书等平台且已通过原路会话直接回复成功，
        // 则在普通转发通道中自动排除同类型的机器人，彻底杜绝群内双重回执轰炸。
        val deduplicatedInstances = if (directDelivered) {
            targetInstances.filterNot { inst ->
                when (pending.source) {
                    SOURCE_DINGTALK -> inst.channelType == org.fossify.messages.forwarding.ForwardingChannels.DINGTALK
                    SOURCE_FEISHU -> inst.channelType == org.fossify.messages.forwarding.ForwardingChannels.FEISHU ||
                                     inst.channelType == org.fossify.messages.forwarding.ForwardingChannels.FEISHU_BOT ||
                                     inst.channelType == org.fossify.messages.forwarding.ForwardingChannels.FEISHU_APP
                    SOURCE_WECOM -> inst.channelType == org.fossify.messages.forwarding.ForwardingChannels.WECOM ||
                                    inst.channelType == org.fossify.messages.forwarding.ForwardingChannels.WECOM_BOT ||
                                    inst.channelType == org.fossify.messages.forwarding.ForwardingChannels.WECOM_APP
                    SOURCE_TELEGRAM -> inst.channelType == org.fossify.messages.forwarding.ForwardingChannels.TELEGRAM
                    SOURCE_WEBSOCKET -> inst.channelType == org.fossify.messages.forwarding.ForwardingChannels.WEBSOCKET
                    SOURCE_EMAIL -> inst.channelType == org.fossify.messages.forwarding.ForwardingChannels.EMAIL
                    else -> false
                }
            }
        } else {
            targetInstances
        }

        if (deduplicatedInstances.isNotEmpty()) {
            deduplicatedInstances.forEach { inst ->
                MultiChannelForwardWorker.enqueueSingle(
                    context = context,
                    sender = title,
                    body = body,
                    receivedAt = System.currentTimeMillis(),
                    subscriptionId = -1,
                    uniqueId = "$uniqueId-${inst.id}",
                    targetChannel = inst.channelType,
                    targetInstanceId = inst.id,
                    allowedChannels = setOf(inst.id),
                    isTest = false,
                )
            }
        }
    }
}
