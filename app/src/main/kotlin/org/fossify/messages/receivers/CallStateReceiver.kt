package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.forwarding.CallForwardConfig
import org.fossify.messages.forwarding.ForwardingMessageFormatter
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 纯本地电话状态与未接来电广播监听器
 * 支持 goAsync 异步派发与 SharedPreferences 暂态持久化（防进程被杀导致状态丢失）
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val callConfig = CallForwardConfig(context)
        if (!callConfig.enabled) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        val subId = intent.getIntExtra("subscription", -1)

        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_TRANSIENT, Context.MODE_PRIVATE)

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val num = incomingNumber.ifBlank { prefs.getString(KEY_LAST_NUMBER, "").orEmpty() }
                prefs.edit()
                    .putString(KEY_LAST_NUMBER, num)
                    .putLong(KEY_RING_START, now)
                    .putBoolean(KEY_ANSWERED, false)
                    .apply()
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                prefs.edit()
                    .putBoolean(KEY_ANSWERED, true)
                    .putLong(KEY_CALL_START, now)
                    .apply()
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val ringStart = prefs.getLong(KEY_RING_START, 0L)
                val isAnswered = prefs.getBoolean(KEY_ANSWERED, false)
                val callStart = prefs.getLong(KEY_CALL_START, 0L)
                val number = incomingNumber.ifBlank { prefs.getString(KEY_LAST_NUMBER, "").orEmpty() }

                // 立即重置暂态缓存
                prefs.edit().clear().apply()

                if (number.isNotBlank() && ringStart > 0L) {
                    val pendingResult = goAsync()
                    val appContext = context.applicationContext

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val ringDurationSeconds = ((now - ringStart) / 1000).coerceAtLeast(1)
                            val wasMissed = !isAnswered

                            if (wasMissed && callConfig.missedCallOnly) {
                                dispatchCallNotification(
                                    context = appContext,
                                    callerNumber = number,
                                    isMissed = true,
                                    durationSeconds = ringDurationSeconds,
                                    subId = subId,
                                    timestamp = now
                                )
                            } else if (!wasMissed && callConfig.forwardAnsweredCall) {
                                val callDuration = ((now - callStart) / 1000).coerceAtLeast(1)
                                dispatchCallNotification(
                                    context = appContext,
                                    callerNumber = number,
                                    isMissed = false,
                                    durationSeconds = callDuration,
                                    subId = subId,
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
        val simDesc = if (subId >= 0) {
            ForwardingMessageFormatter.getSimDescription(context, multiConfig, subId)
        } else ""

        val body = buildString {
            appendLine(if (isMissed) "🔴 $eventTitle" else "🟢 $eventTitle")
            appendLine("📞 来电号码：$contactDisplay")
            appendLine(if (isMissed) "⏱️ 响铃时长：${durationSeconds}秒" else "⏱️ 通话时长：${durationSeconds}秒")
            appendLine("🕒 发生时间：$timeFormatted")
            if (simDesc.isNotBlank()) {
                appendLine("📶 接收卡槽：$simDesc")
            }
        }.trim()

        val channels = multiConfig.enabledChannelIds()
        val uniqueId = "call-${System.currentTimeMillis()}"

        channels.forEach { target ->
            MultiChannelForwardWorker.enqueueSingle(
                context = context,
                sender = callerNumber,
                body = body,
                receivedAt = timestamp,
                subscriptionId = subId,
                uniqueId = uniqueId,
                targetChannel = target,
                allowedChannels = setOf(target),
                isTest = false
            )
        }
    }

    companion object {
        private const val PREFS_TRANSIENT = "call_state_transient"
        private const val KEY_LAST_NUMBER = "last_number"
        private const val KEY_RING_START = "ring_start"
        private const val KEY_CALL_START = "call_start"
        private const val KEY_ANSWERED = "answered"
    }
}
