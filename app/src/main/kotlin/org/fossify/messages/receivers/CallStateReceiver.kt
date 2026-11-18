package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.forwarding.CallForwardConfig
import org.fossify.messages.forwarding.ForwardingMessageFormatter
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.repository.ChannelRepository

/** 纯本地电话状态与未接来电广播监听器 支持 goAsync 异步派发与 SharedPreferences 暂态持久化（防进程被杀导致状态丢失） */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val callConfig = CallForwardConfig(context)
        if (!callConfig.enabled) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        val subId =
            org.fossify.messages.messaging.ReceivingSubscriptionReader.read(context, intent)
                .confirmedSubscriptionId ?: -1

        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_TRANSIENT, Context.MODE_PRIVATE)

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val answered = prefs.getBoolean(KEY_ANSWERED, false)
                val prevCallStart = prefs.getLong(KEY_CALL_START, 0L)
                if (answered && prevCallStart > 0L && now - prevCallStart <= MAX_CALL_AGE_MS) {
                    return
                }
                if (answered) {
                    prefs.edit().clear().apply()
                }
                val existingRingStart = prefs.getLong(KEY_RING_START, 0L)
                    .takeIf { it > 0L && now - it <= MAX_RING_AGE_MS }
                    ?: 0L
                val num = incomingNumber.ifBlank {
                    if (existingRingStart > 0L) prefs.getString(KEY_LAST_NUMBER, "").orEmpty() else ""
                }
                val rememberedSubId = if (existingRingStart > 0L) prefs.getInt(KEY_RECEIVING_SUB, -1) else -1
                val identityConflict =
                    (existingRingStart > 0L && prefs.getBoolean("receiving_conflict", false)) ||
                        (rememberedSubId >= 0 && subId >= 0 && rememberedSubId != subId)
                prefs.edit()
                    .putBoolean("receiving_conflict", identityConflict)
                    .putString(KEY_LAST_NUMBER, num)
                    .putInt(KEY_RECEIVING_SUB,
                        if (identityConflict) -1 else rememberedSubId.takeIf { it >= 0 } ?: subId)
                    // Android may deliver the same state twice, with and without a number.
                    .putLong(KEY_RING_START, existingRingStart.takeIf { it > 0L } ?: now)
                    .putBoolean(KEY_ANSWERED, false)
                    .apply()
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val existingRingStart = prefs.getLong(KEY_RING_START, 0L)
                if (existingRingStart > 0L && now - existingRingStart <= MAX_RING_AGE_MS) {
                    prefs.edit()
                        .putBoolean(KEY_ANSWERED, true)
                        .putLong(KEY_CALL_START, now)
                        .apply()
                } else {
                    prefs.edit().clear().apply()
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val ringStart = prefs.getLong(KEY_RING_START, 0L)
                val isAnswered = prefs.getBoolean(KEY_ANSWERED, false)
                val callStart = prefs.getLong(KEY_CALL_START, 0L)
                val number = incomingNumber.ifBlank { prefs.getString(KEY_LAST_NUMBER, "").orEmpty() }
                val identityConflict = prefs.getBoolean("receiving_conflict", false)
                val receivingSubId =
                    if (identityConflict) -1
                    else prefs.getInt(KEY_RECEIVING_SUB, -1).takeIf { it >= 0 } ?: subId

                // 立即重置暂态缓存
                prefs.edit().clear().apply()

                if (number.isNotBlank() && ringStart > 0L && now - ringStart <= MAX_CALL_AGE_MS) {
                    val pendingResult = goAsync()
                    val appContext = context.applicationContext

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val confirmedCallSub =
                                if (identityConflict) -1
                                else
                                    org.fossify.messages.messaging.ReceivingCallIdentity.resolve(
                                        appContext,
                                        number,
                                        ringStart,
                                        now,
                                        receivingSubId,
                                    )
                            val ringDurationSeconds = ((now - ringStart) / 1000).coerceAtLeast(1)
                            val wasMissed = !isAnswered

                            // “仅未接来电”开启时，若能从通话记录确认为用户主动拒接（REJECTED_TYPE），则忽略不转发
                            if (wasMissed) {
                                if (callConfig.missedCallOnly && isRejectedInCallLog(appContext, number, ringStart)) {
                                    return@launch
                                }
                                dispatchCallNotification(
                                    context = appContext,
                                    callerNumber = number,
                                    isMissed = true,
                                    durationSeconds = ringDurationSeconds,
                                    subId = confirmedCallSub,
                                    timestamp = now
                                )
                            } else if (callConfig.forwardAnsweredCall) {
                                val baseStart = callStart.takeIf { it > 0L } ?: ringStart
                                val callDuration = ((now - baseStart) / 1000).coerceAtLeast(1)
                                dispatchCallNotification(
                                    context = appContext,
                                    callerNumber = number,
                                    isMissed = false,
                                    durationSeconds = callDuration,
                                    subId = confirmedCallSub,
                                    timestamp = now
                                )
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    private fun dispatchCallNotification(
        context: Context,
        callerNumber: String,
        isMissed: Boolean,
        durationSeconds: Long,
        subId: Int,
        timestamp: Long
    ) {
        val contactName = runCatching {
            context.getNameAndPhotoFromPhoneNumber(callerNumber).name
        }.getOrNull()?.takeIf { it.isNotBlank() && it != callerNumber }

        val contactDisplay = if (contactName != null) "$contactName ($callerNumber)" else callerNumber
        val timeFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val eventTitle = if (isMissed) "【未接来电提醒】" else "【通话结束提醒】"

        val multiConfig = MultiForwardConfig(context)
        val slotName = ForwardingMessageFormatter.getSimSlotName(context, multiConfig, subId)
        val description = ForwardingMessageFormatter.getSimDescription(context, multiConfig, subId)
        val simDesc = if (description == slotName || description.startsWith("$slotName ·")) description
            else if (slotName == "未知接收卡") slotName else "$slotName · $description"
        val receiverNumber = ForwardingMessageFormatter.getReceiverNumber(context, multiConfig, subId)

        val defaultBody = buildString {
            appendLine(if (isMissed) "🔴 $eventTitle" else "🟢 $eventTitle")
            appendLine("📞 来电号码：$contactDisplay")
            appendLine(if (isMissed) "⏱️ 响铃时长：${durationSeconds}秒" else "⏱️ 通话时长：${durationSeconds}秒")
            appendLine("🕒 发生时间：$timeFormatted")
            appendLine("📶 接收卡槽：$simDesc")
            if (receiverNumber.isNotBlank()) appendLine("📱 本机接收号码：$receiverNumber")
        }.trim()

        val callConfig = CallForwardConfig(context)
        val body = callConfig.customTemplate.takeIf(String::isNotBlank)?.let { template ->
            template
                .replace("[type]", if (isMissed) "未接来电" else "通话结束")
                .replace("[number]", callerNumber)
                .replace("[name]", contactName.orEmpty())
                .replace("[caller]", contactDisplay)
                .replace("[duration]", durationSeconds.toString())
                .replace("[time]", timeFormatted)
                .replace("[sim]", simDesc)
                .replace("[receiver]", receiverNumber)
        } ?: defaultBody
        val uniqueId = "call-${System.currentTimeMillis()}"
        val allEnabledInstances = ChannelRepository.getInstance(context)
            .getEnabledInstances()
            .filterNot { it.id.startsWith("catalog:") }

        if (callConfig.hasChannelSelection) {
            val selectedIds = callConfig.channelInstanceIds
            allEnabledInstances
                .filter { it.id in selectedIds }
                .forEach { instance ->
                    MultiChannelForwardWorker.enqueueSingle(
                        context = context,
                        sender = callerNumber,
                        body = body,
                        receivedAt = timestamp,
                        subscriptionId = subId,
                        uniqueId = "$uniqueId-${instance.id}",
                        targetChannel = instance.channelType,
                        allowedChannels = setOf(instance.id),
                        isTest = false,
                        targetInstanceId = instance.id,
                        bodyAlreadyRendered = true
                    )
                }
        } else {
            allEnabledInstances.forEach { instance ->
                MultiChannelForwardWorker.enqueueSingle(
                    context = context,
                    sender = callerNumber,
                    body = body,
                    receivedAt = timestamp,
                    subscriptionId = subId,
                    uniqueId = "$uniqueId-${instance.id}",
                    targetChannel = instance.channelType,
                    allowedChannels = setOf(instance.id),
                    isTest = false,
                    targetInstanceId = instance.id,
                    bodyAlreadyRendered = true
                )
            }
            val instanceTypes = allEnabledInstances.map { it.channelType }.toSet()
            multiConfig.enabledChannelIds()
                .filterNot { it in instanceTypes }
                .forEach { target ->
                    MultiChannelForwardWorker.enqueueSingle(
                        context = context,
                        sender = callerNumber,
                        body = body,
                        receivedAt = timestamp,
                        subscriptionId = subId,
                        uniqueId = "$uniqueId-legacy-$target",
                        targetChannel = target,
                        allowedChannels = setOf(target),
                        isTest = false,
                        bodyAlreadyRendered = true
                    )
                }
        }
    }

    private fun isRejectedInCallLog(context: Context, callerNumber: String, ringStart: Long): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            val targetDigits = callerNumber.filter { it.isDigit() }.takeLast(11)
            Thread.sleep(400)
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.NUMBER),
                "${android.provider.CallLog.Calls.DATE} >= ?",
                arrayOf((ringStart - 5_000L).toString()),
                "${android.provider.CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val loggedDigits = cursor.getString(1).orEmpty().filter { it.isDigit() }.takeLast(11)
                    if (targetDigits.isBlank() || loggedDigits == targetDigits) {
                        return@use cursor.getInt(0) == android.provider.CallLog.Calls.REJECTED_TYPE
                    }
                }
                false
            } ?: false
        }.getOrDefault(false)
    }

    companion object {
        private const val PREFS_TRANSIENT = "call_state_transient"
        private const val KEY_LAST_NUMBER = "last_number"
        private const val KEY_RECEIVING_SUB = "receiving_subscription"
        private const val KEY_RING_START = "ring_start"
        private const val KEY_CALL_START = "call_start"
        private const val KEY_ANSWERED = "answered"
        private const val MAX_RING_AGE_MS = 15 * 60_000L
        private const val MAX_CALL_AGE_MS = 4 * 3600_000L
    }
}
