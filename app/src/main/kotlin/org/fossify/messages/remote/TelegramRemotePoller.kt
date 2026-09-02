package org.fossify.messages.remote

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.helpers.RemoteCommandRepository
import org.fossify.messages.messaging.SimSendResolver
import org.fossify.messages.models.RemoteCommandContext
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.models.RemoteCommandType
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TelegramRemotePoller(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()
    private val running = AtomicBoolean(false)
    private var lastUpdateId = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            onStatus("已启动 Telegram Bot 长轮询…")
            while (running.get()) {
                try {
                    pollUpdates()
                } catch (e: Throwable) {
                    Log.e(TAG, "TG poll error", e)
                    onStatus("轮询异常：${e.message ?: e.javaClass.simpleName}")
                    try {
                        Thread.sleep(5_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.apply {
            name = "tg-remote-poller"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
    }

    private fun pollUpdates() {
        val config = MultiForwardConfig(context)
        val token = config.telegramRemoteBotToken()
        if (token.isBlank()) {
            onStatus("缺少 Telegram Bot Token")
            Thread.sleep(10_000)
            return
        }
        val baseUrl = config.telegramRemoteCustomHost().trim().ifBlank { "https://api.telegram.org" }
            .trimEnd('/')
        val url = "$baseUrl/bot$token/getUpdates?offset=${lastUpdateId + 1}&timeout=25"

        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) {
                onStatus("HTTP ${response.code}: $body")
                return
            }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) {
                onStatus("TG API 错误：${json.optString("description")}")
                return
            }
            val result = json.optJSONArray("result") ?: return
            for (i in 0 until result.length()) {
                val update = result.optJSONObject(i) ?: continue
                val updateId = update.optLong("update_id", 0L)
                if (updateId > lastUpdateId) {
                    lastUpdateId = updateId
                }
                processUpdate(update, baseUrl, token)
            }
        }
    }

    private fun processUpdate(update: JSONObject, baseUrl: String, token: String) {
        val message = update.optJSONObject("message") ?: update.optJSONObject("channel_post") ?: return
        val text = message.optString("text").trim()
        if (text.isBlank()) return

        val from = message.optJSONObject("from")
        val chat = message.optJSONObject("chat")
        val senderId = from?.optLong("id", 0L)?.toString() ?: ""
        val chatId = chat?.optLong("id", 0L)?.toString() ?: ""
        val messageId = message.optLong("message_id", 0L).toString()

        val config = MultiForwardConfig(context)
        val authUsers = config.telegramRemoteAuthorizedUsers().split('\n', ',', ';', '，', '；')
            .map(String::trim).filter(String::isNotBlank)
        val defaultChatId = config.telegramRemoteChatId().trim()

        if (authUsers.isNotEmpty()) {
            val authorized = authUsers.contains(senderId) || authUsers.contains(chatId)
            if (!authorized) {
                config.appendTelegramRemoteLog("忽略未授权 Telegram 用户/群组 [$senderId / $chatId]")
                return
            }
        } else if (defaultChatId.isNotBlank() && chatId != defaultChatId && senderId != defaultChatId) {
            config.appendTelegramRemoteLog("忽略非主绑定 ChatID 指令 [$chatId]")
            return
        }

        val command = RemoteSmsCommand.parse(text, config.telegramRemoteCustomPrefix()) ?: return
        val messageKey = "tg-$chatId-$messageId"
        val sendMode = command.effectiveSendMode(config.telegramRemoteSendSimMode)

        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.TELEGRAM,
            sourceMessageKey = messageKey,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = command.targetNumber,
            rawPayload = command.content,
            requestedSimMode = sendMode,
            rawRequester = "tg:$chatId",
            receivedAt = System.currentTimeMillis(),
        )

        val claimResult = runBlocking {
            RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        }

        if (claimResult is RemoteCommandRepository.ClaimResult.Duplicate) {
            config.appendTelegramRemoteLog("抑制重复指令 -> ${command.targetNumber}")
            return
        }

        val commandId = (claimResult as? RemoteCommandRepository.ClaimResult.NewCommand)?.commandId.orEmpty()
        val fingerprint = "tg-${sha256(messageKey)}"
        val simSuffix = " · ${SimSendResolver.describeForLog(context, null, sendMode)}"
        config.appendTelegramRemoteLog("收到指令 -> ${command.targetNumber}$simSuffix (Chat: $chatId)")

        val rulesConfig = ForwardingRulesConfig(context)
        if (rulesConfig.affectsRemoteCommands() && rulesConfig.rules.any { it.enabled }) {
            val decision = ForwardingRuleEngine(rulesConfig.rules).evaluate(
                sender = SOURCE_TELEGRAM,
                body = "${command.targetNumber} ${command.content}",
                subscriptionId = -1,
                channelCandidates = emptySet(),
                simSlotIndex = null,
            )
            if (decision.matchedRules.isEmpty()) {
                if (commandId.isNotBlank()) {
                    RemoteCommandRepository.recordAuthorization(context, commandId, authorized = false, reason = "RULE_BLOCKED")
                }
                config.appendTelegramRemoteLog("规则阻止执行 -> ${command.targetNumber}$simSuffix")
                return
            }
        }

        if (commandId.isNotBlank()) {
            RemoteCommandRepository.recordAuthorization(context, commandId, authorized = true, reason = "TG_AUTHORIZED")
        }

        RemoteSmsCommandWorker.enqueue(
            context = context,
            target = command.targetNumber,
            content = command.content,
            subId = -1,
            uniqueId = fingerprint,
            sendMode = sendMode,
            requester = chatId,
            source = SOURCE_TELEGRAM,
            commandId = commandId,
        )
    }

    companion object {
        private const val TAG = "TelegramRemotePoller"

        fun sendReply(context: Context, chatId: String, text: String) {
            if (chatId.isBlank() || text.isBlank()) return
            Thread {
                try {
                    val config = MultiForwardConfig(context)
                    val token = config.telegramRemoteBotToken()
                    if (token.isBlank()) return@Thread
                    val baseUrl = config.telegramRemoteCustomHost().trim().ifBlank { "https://api.telegram.org" }
                        .trimEnd('/')
                    val payload = JSONObject()
                        .put("chat_id", chatId)
                        .put("text", text)
                    val request = Request.Builder()
                        .url("$baseUrl/bot$token/sendMessage")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    OkHttpClient().newCall(request).execute().close()
                } catch (e: Throwable) {
                    Log.e(TAG, "Reply send error", e)
                }
            }.start()
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}