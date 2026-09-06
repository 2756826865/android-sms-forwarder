package org.fossify.messages.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.telephony.SubscriptionManager
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.remote.RemoteControlPendingReceipt
import org.fossify.messages.remote.RemoteControlReceiptForwarder
import org.fossify.messages.messaging.SimSendResolver
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.helpers.RemoteCommandRepository
import org.fossify.messages.models.RemoteCommandContext
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.models.RemoteCommandType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RemoteSmsCommandConfig(
    context: Context? = null,
    customPrefs: SharedPreferences? = null
) {
    private val prefs: SharedPreferences = customPrefs
        ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?: error("Context or customPrefs required")

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

    var customPrefix: String
        get() = prefs.getString(KEY_CUSTOM_PREFIX, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CUSTOM_PREFIX, value.trim()).apply()

    companion object {
        private const val PREFS_NAME = "remote_sms_command"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTHORIZED_NUMBERS = "authorized_numbers"
        private const val KEY_CUSTOM_PREFIX = "custom_prefix"
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
        fun parse(text: String, customPrefix: String = ""): RemoteSmsCommand? {
            val trimmed = text.trim()
            val prefixes = mutableListOf("/短信发送", "/发信", "/发短信", "#发信", "#发短信")
            if (customPrefix.isNotBlank()) {
                prefixes.add(0, customPrefix.trim())
            }

            var matchedPrefix: String? = null
            var commandText = trimmed
            for (p in prefixes) {
                if (trimmed.startsWith(p)) {
                    matchedPrefix = p
                    commandText = trimmed
                    break
                }
            }
            if (matchedPrefix == null) return null
            val remainder = trimmed.substring(matchedPrefix.length).trim()
            if (remainder.isBlank()) return null

            val firstSpace = remainder.indexOfFirst { it.isWhitespace() }
            if (firstSpace < 0) return null // 仅有1个token（无论是SIM还是号码），缺少内容

            val firstToken = remainder.substring(0, firstSpace).trim()
            val afterFirst = remainder.substring(firstSpace).trim()

            val isSimToken = firstToken.equals("SIM1", ignoreCase = true) ||
                firstToken.equals("SIM2", ignoreCase = true) ||
                firstToken == "默认" || firstToken == "系统默认"

            val simToken: String?
            val target: String
            val content: String

            if (isSimToken) {
                val secondSpace = afterFirst.indexOfFirst { it.isWhitespace() }
                if (secondSpace < 0) return null // 有SIM且有号码，但缺少内容
                simToken = firstToken
                target = afterFirst.substring(0, secondSpace).trim()
                content = afterFirst.substring(secondSpace).trim()
            } else {
                simToken = null
                target = firstToken
                content = afterFirst
            }

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
        messageId: Long = 0L,
        allowExecution: Boolean = true,
    ): Boolean {
        val config = RemoteSmsCommandConfig(context)
        val smsSources = org.fossify.messages.remote.repository.RemoteSourceRepository
            .getInstance(context)
            .getSourcesByType(org.fossify.messages.remote.repository.RemoteSourceType.SMS)
            .filter { it.enabled }

        // 同类型多实例不能固定取 firstOrNull。优先选择白名单真正匹配发件人的实例，
        // 其次选择关闭白名单的实例；相同条件下优先更长、更具体的自定义前缀。
        val matchingSources = smsSources
            .mapNotNull { source ->
                val prefix = source.customCommandPrefix.ifBlank { config.customPrefix }
                RemoteSmsCommand.parse(body, prefix)?.let { source to prefix }
            }
            .sortedByDescending { (_, prefix) -> prefix.length }
        val selectedSource = matchingSources.firstOrNull { (source, _) ->
            source.whitelistEnabled && source.authorizedUsers.any { numbersEquivalent(it, sender) }
        }?.first ?: matchingSources.firstOrNull { (source, _) -> !source.whitelistEnabled }?.first
            ?: matchingSources.firstOrNull()?.first

        val legacyPrefix = config.customPrefix
        val legacySyntaxMatched = RemoteSmsCommand.parse(body, legacyPrefix) != null
        // 非指令内容立即放行走普通短信转发；存在实例时只采用实例自己的前缀。
        if (selectedSource == null && (smsSources.isNotEmpty() || !legacySyntaxMatched)) return false
        if (!allowExecution) {
            config.appendLog("远程短信指令已被转发规则阻止：$sender")
            return true
        }

        val messageKey = if (messageId > 0L) "id:$messageId" else "time:$messageTimestamp"
        val envelope = RemoteCommandEnvelope(
            sourceType = org.fossify.messages.remote.repository.RemoteSourceType.SMS,
            sourceInstanceId = selectedSource?.id.orEmpty(),
            sourceMessageKey = messageKey,
            senderId = sender,
            rawContent = body,
            receivedAt = messageTimestamp,
            subscriptionId = subscriptionId,
            messageId = messageId
        )

        return when (val result = RemoteCommandProcessor.process(context, envelope)) {
            is RemoteProcessResult.Success -> {
                config.appendLog("指令已加入发送队列：$sender -> ${result.target}")
                true
            }
            is RemoteProcessResult.Duplicate -> {
                config.appendLog("抑制重复指令：$sender")
                true
            }
            is RemoteProcessResult.Rejected -> {
                config.appendLog("指令已被拒绝：${result.detail.ifBlank { result.reason }}")
                true
            }
            is RemoteProcessResult.Ignored -> false
        }
    }

    private fun numbersEquivalent(a: String, b: String): Boolean {
        val left = a.filter(Char::isDigit).takeLast(11)
        val right = b.filter(Char::isDigit).takeLast(11)
        return left.isNotEmpty() && (left == right || left.endsWith(right) || right.endsWith(left))
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
        val commandId = inputData.getString(KEY_COMMAND_ID).orEmpty()
        val sourceInstanceId = inputData.getString(KEY_SOURCE_INSTANCE_ID).orEmpty()

        if (target.isBlank() || content.isBlank()) {
            if (commandId.isNotBlank()) {
                RemoteCommandRepository.recordExecutionFailure(
                    applicationContext,
                    commandId,
                    errorClass = "IllegalArgumentException",
                    errorMessage = "Target or content is blank"
                )
            }
            return Result.failure()
        }

        if (commandId.isNotBlank()) {
            RemoteCommandRepository.recordRunning(applicationContext, commandId)
        }

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
            commandId = commandId,
            sourceInstanceId = sourceInstanceId,
        )
        if (resolvedSubId == null && sendMode in setOf(SimSendResolver.MODE_SIM1, SimSendResolver.MODE_SIM2)) {
            val error = "未找到可用的${SimSendResolver.modeLabel(sendMode)}"
            appendRemoteLog(source, "发送失败：$target$simLogSuffix，$error")
            RemoteControlReceiptForwarder.forwardImmediate(applicationContext, error, pendingReceipt)
            if (commandId.isNotBlank()) {
                RemoteCommandRepository.recordExecutionFailure(
                    applicationContext,
                    commandId,
                    errorClass = "SimUnavailableException",
                    errorMessage = error
                )
            }
            return Result.failure()
        }
        if (uniqueId.isNotBlank() && !RemoteSmsCommandConfig(applicationContext).claimFingerprint("execution:$uniqueId")) {
            appendRemoteLog(source, "抑制重复执行：$target$simLogSuffix")
            return Result.success()
        }
        if (commandId.isNotBlank()) {
            RemoteCommandRepository.recordSubmitting(applicationContext, commandId)
        }
        val triggerType = when (source) {
            SOURCE_DINGTALK -> org.fossify.messages.models.SmsSendTriggerType.REMOTE_DINGTALK_COMMAND
            SOURCE_FEISHU -> org.fossify.messages.models.SmsSendTriggerType.REMOTE_FEISHU_COMMAND
            SOURCE_EMAIL -> org.fossify.messages.models.SmsSendTriggerType.REMOTE_EMAIL_COMMAND
            SOURCE_TELEGRAM -> org.fossify.messages.models.SmsSendTriggerType.REMOTE_TELEGRAM_COMMAND
            SOURCE_WEBSOCKET -> org.fossify.messages.models.SmsSendTriggerType.REMOTE_WEBSOCKET_COMMAND
            else -> org.fossify.messages.models.SmsSendTriggerType.REMOTE_SMS_COMMAND
        }
        return runCatching {
            val uris = applicationContext.messagingUtils.sendSmsMessage(
                text = content,
                addresses = setOf(target),
                subId = sendSubId,
                requireDeliveryReport = applicationContext.config.enableDeliveryReports,
                triggerType = triggerType
            )
            RemoteControlReceiptForwarder.registerFromMessageUris(applicationContext, uris, pendingReceipt)
            appendRemoteLog(source, "已提交发送：$target$simLogSuffix")
            if (commandId.isNotBlank()) {
                val providerMsgId = uris.firstOrNull()?.lastPathSegment?.toLongOrNull()
                val sendOpId = if (providerMsgId != null) {
                    applicationContext.getMessagesDB().SmsSendDao().getOperationByProviderMessageId(providerMsgId)?.sendOperationId
                } else null
                RemoteCommandRepository.recordSubmitted(applicationContext, commandId, sendOpId)
            }
            Result.success()
        }.getOrElse { error ->
            appendRemoteLog(source, "发送失败：$target$simLogSuffix，${error.message ?: error.javaClass.simpleName}")
            RemoteControlReceiptForwarder.forwardImmediate(
                applicationContext,
                "提交失败：${error.message ?: error.javaClass.simpleName}",
                pendingReceipt,
            )
            if (commandId.isNotBlank()) {
                RemoteCommandRepository.recordExecutionFailure(
                    applicationContext,
                    commandId,
                    errorClass = error.javaClass.name,
                    errorMessage = error.message
                )
            }
            Result.failure()
        }
    }

    private fun appendRemoteLog(source: String, message: String) {
        RemoteSmsCommandConfig(applicationContext).appendLog(message)
        val multiConfig = MultiForwardConfig(applicationContext)
        when (source) {
            SOURCE_DINGTALK -> multiConfig.appendDingTalkRemoteLog(message)
            SOURCE_FEISHU -> multiConfig.appendFeishuRemoteLog(message)
            SOURCE_EMAIL -> multiConfig.appendEmailRemoteLog(message)
            SOURCE_TELEGRAM -> multiConfig.appendTelegramRemoteLog(message)
            SOURCE_WEBSOCKET -> multiConfig.appendWebSocketRemoteLog(message)
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
        private const val KEY_COMMAND_ID = "command_id"
        private const val KEY_SOURCE_INSTANCE_ID = "source_instance_id"

        fun enqueue(
            context: Context,
            target: String,
            content: String,
            subId: Int,
            uniqueId: String,
            sendMode: Int = SimSendResolver.MODE_FOLLOW_RECEIVE,
            requester: String = "",
            source: String = SOURCE_SMS,
            commandId: String = "",
            sourceInstanceId: String = "",
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
                        KEY_COMMAND_ID to commandId,
                        KEY_SOURCE_INSTANCE_ID to sourceInstanceId,
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
const val SOURCE_FEISHU = "飞书远程指令"
const val SOURCE_EMAIL = "邮箱远程指令"
const val SOURCE_TELEGRAM = "Telegram远程指令"
const val SOURCE_WEBSOCKET = "WebSocket远程指令"

private fun normalizeNumber(value: String): String = value.filter(Char::isDigit).takeLast(11)

private fun numbersEquivalent(a: String, b: String): Boolean {
    val left = normalizeNumber(a)
    val right = normalizeNumber(b)
    return left.isNotBlank() && (left == right || a.trim() == b.trim())
}
