package org.fossify.messages.remote

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.telephony.SubscriptionManager
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.remote.RemoteControlPendingReceipt
import org.fossify.messages.remote.RemoteControlReceiptForwarder
import org.fossify.messages.messaging.SimSendResolver
import org.fossify.messages.forwarding.MultiForwardConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RemoteSmsCommandConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var authorizedNumbers: String
        get() = prefs.getString(KEY_AUTHORIZED_NUMBERS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_AUTHORIZED_NUMBERS, value.trim()).apply()

    var lastStatus: String
        get() = prefs.getString(KEY_LAST_STATUS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_STATUS, value).apply()

    fun authorizedList(): List<String> = authorizedNumbers
        .split('\n', ',', ';', '，', '；')
        .map(String::trim)
        .filter(String::isNotBlank)

    fun isAuthorized(sender: String): Boolean = authorizedList().any { numbersEquivalent(it, sender) }

    fun claimFingerprint(fingerprint: String, now: Long = System.currentTimeMillis()): Boolean {
        synchronized(fingerprintLock) {
            val recent = decodeFingerprints(prefs.getString(KEY_RECENT_FINGERPRINTS, "[]").orEmpty())
                .filter { now - it.claimedAt in 0L..DUPLICATE_WINDOW_MS }
            if (recent.any { it.value == fingerprint }) return false

            val updated = (recent + FingerprintEntry(fingerprint, now)).takeLast(MAX_RECENT_FINGERPRINTS)
            val encoded = JSONArray().apply {
                updated.forEach { entry ->
                    put(JSONObject().put("value", entry.value).put("claimedAt", entry.claimedAt))
                }
            }.toString()
            return prefs.edit().putString(KEY_RECENT_FINGERPRINTS, encoded).commit()
        }
    }

    fun isRateLimited(sender: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = KEY_RATE_PREFIX + normalizeNumber(sender)
        val values = prefs.getString(key, "[]").orEmpty()
        val recent = decodeLongArray(values).filter { now - it < RATE_WINDOW_MS }
        return recent.size >= RATE_LIMIT_COUNT
    }

    fun markExecution(sender: String, now: Long = System.currentTimeMillis()) {
        val key = KEY_RATE_PREFIX + normalizeNumber(sender)
        val recent = decodeLongArray(prefs.getString(key, "[]").orEmpty())
            .filter { now - it < RATE_WINDOW_MS } + now
        prefs.edit().putString(key, JSONArray(recent).toString()).apply()
    }

    fun appendLog(message: String) {
        val now = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$now $message"
        val current = prefs.getString(KEY_LOGS, "").orEmpty().lines().filter(String::isNotBlank)
        val logs = (listOf(line) + current).take(MAX_LOG_LINES).joinToString("\n")
        prefs.edit().putString(KEY_LOGS, logs).putString(KEY_LAST_STATUS, line).apply()
    }

    fun logs(): String = prefs.getString(KEY_LOGS, "").orEmpty()

    fun summary(): String = if (!enabled) {
        "未启用 · 远程短信命令不会执行"
    } else {
        "已启用 · 授权号码 ${authorizedList().size} 个"
    }

    private fun decodeLongArray(value: String): List<Long> = runCatching {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) add(array.optLong(index))
        }
    }.getOrDefault(emptyList())

    private fun decodeFingerprints(value: String): List<FingerprintEntry> = runCatching {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val fingerprint = item.optString("value")
                val claimedAt = item.optLong("claimedAt")
                if (fingerprint.isNotBlank() && claimedAt > 0L) {
                    add(FingerprintEntry(fingerprint, claimedAt))
                }
            }
        }
    }.getOrDefault(emptyList())

    private data class FingerprintEntry(val value: String, val claimedAt: Long)

    companion object {
        private const val PREFS_NAME = "remote_sms_command"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTHORIZED_NUMBERS = "authorized_numbers"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LOGS = "logs"
        private const val KEY_RECENT_FINGERPRINTS = "recent_fingerprints"
        private const val KEY_RATE_PREFIX = "rate_"
        private const val DUPLICATE_WINDOW_MS = 10 * 60 * 1000L
        private const val MAX_RECENT_FINGERPRINTS = 100
        private const val RATE_WINDOW_MS = 60 * 60 * 1000L
        private const val RATE_LIMIT_COUNT = 5
        private const val MAX_LOG_LINES = 30
        private val fingerprintLock = Any()
    }
}

data class RemoteSmsCommand(
    val targetNumber: String,
    val content: String,
    val sendMode: Int = SimSendResolver.MODE_FOLLOW_RECEIVE,
) {
    fun effectiveSendMode(fallbackWhenUnspecified: Int): Int =
        if (sendMode != SimSendResolver.MODE_FOLLOW_RECEIVE) sendMode else fallbackWhenUnspecified

    fun sendModeLabel(): String = SimSendResolver.modeLabel(sendMode)

    companion object {
        private val pattern = Regex(
            "^/短信发送\\s+(?:(SIM1|SIM2|默认|系统默认)\\s+)?(\\S+)\\s+([\\s\\S]+)$",
            RegexOption.IGNORE_CASE,
        )

        fun parse(text: String): RemoteSmsCommand? {
            val trimmed = text.trim()
            val commandStart = trimmed.indexOf("/短信发送")
            val commandText = if (commandStart >= 0) trimmed.substring(commandStart) else trimmed
            val match = pattern.matchEntire(commandText.trim()) ?: return null
            val simToken = match.groupValues[1].trim().takeIf(String::isNotBlank)
            val target = match.groupValues[2].trim()
            val content = match.groupValues[3].trim()
            if (target.isBlank() || content.isBlank()) return null
            return RemoteSmsCommand(target, content, parseSimMode(simToken))
        }

        private fun parseSimMode(token: String?): Int = when {
            token == null -> SimSendResolver.MODE_FOLLOW_RECEIVE
            token.equals("SIM1", ignoreCase = true) -> SimSendResolver.MODE_SIM1
            token.equals("SIM2", ignoreCase = true) -> SimSendResolver.MODE_SIM2
            token == "默认" || token == "系统默认" -> SimSendResolver.MODE_DEFAULT
            else -> SimSendResolver.MODE_FOLLOW_RECEIVE
        }
    }
}

object RemoteSmsCommandProcessor {
    fun tryConsume(
        context: Context,
        sender: String,
        body: String,
        subscriptionId: Int,
        messageTimestamp: Long,
        allowExecution: Boolean = true,
    ): Boolean {
        val command = RemoteSmsCommand.parse(body) ?: return false
        if (!allowExecution) {
            RemoteSmsCommandConfig(context).appendLog("规则阻止执行：$sender")
            return true
        }
        val config = RemoteSmsCommandConfig(context)
        val fingerprint = fingerprint(sender, body, messageTimestamp, subscriptionId)
        if (!config.enabled) {
            config.appendLog("忽略未启用命令：$sender")
            return true
        }
        if (!config.isAuthorized(sender)) {
            config.appendLog("拒绝未授权号码：$sender")
            return true
        }
        if (config.isRateLimited(sender)) {
            config.appendLog("触发频率限制：$sender")
            return true
        }
        if (!config.claimFingerprint(fingerprint)) {
            config.appendLog(
                "抑制重复命令：$sender -> ${command.targetNumber}${simLogSuffix(context, subscriptionId, command.sendMode)}",
            )
            return true
        }
        config.markExecution(sender)
        RemoteSmsCommandWorker.enqueue(
            context,
            command.targetNumber,
            command.content,
            subscriptionId,
            fingerprint,
            sendMode = command.sendMode,
            requester = sender,
            source = SOURCE_SMS,
        )
        config.appendLog(
            "已加入发送队列：$sender -> ${command.targetNumber}${simLogSuffix(context, subscriptionId, command.sendMode)}",
        )
        return true
    }

    private fun simLogSuffix(context: Context, receiveSubId: Int, sendMode: Int): String =
        " · ${SimSendResolver.describeForLog(context, receiveSubId.takeIf { it >= 0 }, sendMode)}"

    private fun fingerprint(sender: String, body: String, messageTimestamp: Long, subscriptionId: Int): String {
        val raw = "$sender\u0000$body\u0000$messageTimestamp\u0000$subscriptionId"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

class RemoteSmsCommandWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val target = inputData.getString(KEY_TARGET).orEmpty()
        val content = inputData.getString(KEY_CONTENT).orEmpty()
        val uniqueId = inputData.getString(KEY_UNIQUE_ID).orEmpty()
        val subId = inputData.getInt(KEY_SUB_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val sendMode = inputData.getInt(KEY_SEND_MODE, SimSendResolver.MODE_FOLLOW_RECEIVE)
        val requester = inputData.getString(KEY_REQUESTER).orEmpty()
        val source = inputData.getString(KEY_SOURCE).orEmpty().ifBlank { SOURCE_SMS }
        if (target.isBlank() || content.isBlank()) return Result.failure()
        val resolvedSubId = SimSendResolver.resolveSubscriptionId(
            applicationContext,
            receiveSubId = subId.takeIf { it >= 0 },
            configuredMode = sendMode,
        )
        val sendSubId = resolvedSubId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val sendSimLabel = SimSendResolver.describeForLog(
            applicationContext,
            subId.takeIf { it >= 0 },
            sendMode,
        )
        val simLogSuffix = " · $sendSimLabel"
        val pendingReceipt = RemoteControlPendingReceipt(
            target = target,
            content = content,
            source = source,
            requester = requester,
            awaitDelivered = false,
            sendSimLabel = sendSimLabel,
        )
        if (resolvedSubId == null && sendMode in setOf(SimSendResolver.MODE_SIM1, SimSendResolver.MODE_SIM2)) {
            val error = "未找到可用的${SimSendResolver.modeLabel(sendMode)}"
            appendRemoteLog(source, "发送失败：$target$simLogSuffix，$error")
            RemoteControlReceiptForwarder.forwardImmediate(applicationContext, error, pendingReceipt)
            return Result.failure()
        }
        if (uniqueId.isNotBlank() && !RemoteSmsCommandConfig(applicationContext).claimFingerprint("execution:$uniqueId")) {
            appendRemoteLog(source, "抑制重复执行：$target$simLogSuffix")
            return Result.success()
        }
        return runCatching {
            val uris = applicationContext.messagingUtils.sendSmsMessage(
                text = content,
                addresses = setOf(target),
                subId = sendSubId,
                requireDeliveryReport = applicationContext.config.enableDeliveryReports,
            )
            RemoteControlReceiptForwarder.registerFromMessageUris(applicationContext, uris, pendingReceipt)
            appendRemoteLog(source, "已提交发送：$target$simLogSuffix")
            Result.success()
        }.getOrElse { error ->
            appendRemoteLog(source, "发送失败：$target$simLogSuffix，${error.message ?: error.javaClass.simpleName}")
            RemoteControlReceiptForwarder.forwardImmediate(
                applicationContext,
                "提交失败：${error.message ?: error.javaClass.simpleName}",
                pendingReceipt,
            )
            Result.failure()
        }
    }

    private fun appendRemoteLog(source: String, message: String) {
        RemoteSmsCommandConfig(applicationContext).appendLog(message)
        if (source == SOURCE_DINGTALK) {
            MultiForwardConfig(applicationContext).appendDingTalkRemoteLog(message)
        }
    }

    companion object {
        private const val KEY_TARGET = "target"
        private const val KEY_CONTENT = "content"
        private const val KEY_UNIQUE_ID = "unique_id"
        private const val KEY_SUB_ID = "sub_id"
        private const val KEY_SEND_MODE = "send_mode"
        private const val KEY_REQUESTER = "requester"
        private const val KEY_SOURCE = "source"

        fun enqueue(
            context: Context,
            target: String,
            content: String,
            subId: Int,
            uniqueId: String,
            sendMode: Int = SimSendResolver.MODE_FOLLOW_RECEIVE,
            requester: String = "",
            source: String = SOURCE_SMS,
        ) {
            val request = OneTimeWorkRequestBuilder<RemoteSmsCommandWorker>()
                .setInputData(
                    workDataOf(
                        KEY_TARGET to target,
                        KEY_CONTENT to content,
                        KEY_UNIQUE_ID to uniqueId,
                        KEY_SUB_ID to subId,
                        KEY_SEND_MODE to sendMode,
                        KEY_REQUESTER to requester,
                        KEY_SOURCE to source,
                    ),
                )
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("remote-sms-command-$uniqueId", ExistingWorkPolicy.KEEP, request)
        }
    }
}

const val SOURCE_SMS = "短信远程指令"
const val SOURCE_DINGTALK = "钉钉远程指令"

private fun normalizeNumber(value: String): String = value.filter(Char::isDigit).takeLast(11)

private fun numbersEquivalent(a: String, b: String): Boolean {
    val left = normalizeNumber(a)
    val right = normalizeNumber(b)
    return left.isNotBlank() && (left == right || a.trim() == b.trim())
}
