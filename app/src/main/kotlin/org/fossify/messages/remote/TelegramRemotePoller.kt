package org.fossify.messages.remote

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.remote.repository.RemoteSourceConnectionState
import org.fossify.messages.remote.repository.RemoteSourceRepository
import org.fossify.messages.remote.repository.RemoteSourceType
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TelegramRemotePoller(
    private val context: Context,
    private val sourceInstanceId: String? = null,
    private val onStatus: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()
    private val running = AtomicBoolean(false)
    private var lastUpdateId = 0L
    private val offsetPrefs = context.getSharedPreferences(PREFS_OFFSET, Context.MODE_PRIVATE)

    private fun offsetKey(): String = "tg_offset_${sourceInstanceId ?: "default"}"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastUpdateId = offsetPrefs.getLong(offsetKey(), 0L)
        Thread {
            onStatus("已启动 Telegram Bot 长轮询 (offset=$lastUpdateId)…")
            while (running.get()) {
                try {
                    pollUpdates()
                } catch (e: Throwable) {
                    Log.e(TAG, "TG poll error", e)
                    val err = "轮询异常：${e.message ?: e.javaClass.simpleName}"
                    onStatus(err)
                    sourceInstanceId?.let { id ->
                        RemoteSourceRepository.getInstance(context)
                            .updateConnectionState(id, RemoteSourceConnectionState.ERROR, errorMessage = err)
                    }
                    try {
                        Thread.sleep(5_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.apply {
            name = "tg-remote-poller-${sourceInstanceId ?: "def"}"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
    }

    private var webhookChecked = false

    private fun checkWebhookConflict(baseUrl: String, token: String, instanceId: String): Boolean {
        if (webhookChecked) return true
        try {
            val url = "$baseUrl/bot$token/getWebhookInfo"
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    val result = json.optJSONObject("result")
                    val currentWebhookUrl = result?.optString("url").orEmpty()
                    if (currentWebhookUrl.isNotBlank()) {
                        Log.w(TAG, "Telegram Bot has existing webhook configured. Long polling conflicts with active webhook.")
                        val tip = "检测到已配置外部 Webhook，Telegram 不允许同时长轮询，请先在 Telegram 解除 Webhook"
                        onStatus(tip)
                        if (instanceId.isNotBlank()) {
                            RemoteSourceRepository.getInstance(context)
                                .updateConnectionState(instanceId, RemoteSourceConnectionState.ERROR, errorMessage = tip)
                        }
                        return false
                    }
                }
            }
            webhookChecked = true
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check webhook info: ${e.message}")
            return true
        }
    }

    enum class UpdateProcessResult {
        CONSUMED,              // 成功解析为指令并派发入队
        IGNORED_PERMANENTLY,    // 确定性永久忽略（非消息类update、空文本、非指令普通文本、重复抑制、白名单拒绝）
        RETRYABLE_FAILURE      // 临时处理异常（内部错误/系统异常），不推进 offset，以便后续轮询重试
    }

    private fun pollUpdates() {
        val repo = RemoteSourceRepository.getInstance(context)
        val instance = sourceInstanceId?.let { repo.getSourceById(it) }
        if (instance == null || !instance.enabled) {
            Thread.sleep(5_000)
            return
        }

        val token = instance.optString("botToken")
        if (token.isBlank()) {
            onStatus("缺少 Telegram Bot Token")
            repo.updateConnectionState(instance.id, RemoteSourceConnectionState.CONFIG_REQUIRED)
            Thread.sleep(10_000)
            return
        }

        val rawHost = instance.optString("customHost")
        val baseUrl = rawHost.trim().ifBlank { "https://api.telegram.org" }.trimEnd('/')

        if (!checkWebhookConflict(baseUrl, token, instance.id)) {
            Thread.sleep(15_000)
            return
        }

        val url = "$baseUrl/bot$token/getUpdates?offset=${lastUpdateId + 1}&timeout=25"

        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) {
                val err = "HTTP ${response.code}: $body"
                onStatus(err)
                repo.updateConnectionState(instance.id, RemoteSourceConnectionState.ERROR, errorCode = response.code, errorMessage = err)
                return
            }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) {
                val desc = json.optString("description")
                val err = "TG API 错误：$desc"
                onStatus(err)
                repo.updateConnectionState(instance.id, RemoteSourceConnectionState.ERROR, errorMessage = err)
                return
            }

            // 标记连接在线就绪
            repo.updateConnectionState(instance.id, RemoteSourceConnectionState.READY)

            val result = json.optJSONArray("result") ?: return
            for (i in 0 until result.length()) {
                val update = result.optJSONObject(i) ?: continue
                val updateId = update.optLong("update_id", 0L)
                val processResult = processUpdate(update, baseUrl, token, instance.id)
                if ((processResult == UpdateProcessResult.CONSUMED || processResult == UpdateProcessResult.IGNORED_PERMANENTLY) && updateId > lastUpdateId) {
                    lastUpdateId = updateId
                    offsetPrefs.edit().putLong(offsetKey(), lastUpdateId).apply()
                } else if (processResult == UpdateProcessResult.RETRYABLE_FAILURE) {
                    Log.w(TAG, "Update $updateId encountered retryable failure; offset remaining at $lastUpdateId")
                    // 必须停止处理本批后续 update。否则后续成功消息会把 offset 推过当前失败消息，
                    // 导致所谓“不推进 offset”实际上仍然永久丢失该指令。
                    break
                }
            }
        }
    }

    private fun processUpdate(update: JSONObject, baseUrl: String, token: String, activeInstanceId: String): UpdateProcessResult {
        val message = update.optJSONObject("message") ?: update.optJSONObject("channel_post")
        if (message == null) {
            // 非消息类 update (如 edited_message、inline_query 等)，永久忽略以推进 offset
            return UpdateProcessResult.IGNORED_PERMANENTLY
        }
        val text = message.optString("text").trim()
        if (text.isBlank()) {
            return UpdateProcessResult.IGNORED_PERMANENTLY
        }

        val from = message.optJSONObject("from")
        val chat = message.optJSONObject("chat")
        val senderId = from?.optLong("id", 0L)?.toString() ?: ""
        val chatId = chat?.optLong("id", 0L)?.toString() ?: ""
        val messageId = message.optLong("message_id", 0L).toString()
        val username = from?.optString("username").orEmpty()
        val isMentioned = text.contains("@")

        val envelope = RemoteCommandEnvelope(
            sourceType = RemoteSourceType.TELEGRAM,
            sourceInstanceId = activeInstanceId,
            sourceMessageKey = "tg-$chatId-$messageId",
            senderId = senderId.ifBlank { chatId },
            senderName = username,
            groupId = if (chatId != senderId) chatId else "",
            rawContent = text,
            isMentioned = isMentioned,
            receivedAt = System.currentTimeMillis(),
            subscriptionId = -1,
            extraMeta = mapOf(
                "chatId" to chatId,
                "messageId" to messageId,
                "receiptTarget" to chatId
            )
        )

        val config = MultiForwardConfig(context)
        return try {
            when (val result = RemoteCommandProcessor.process(context, envelope)) {
                is RemoteProcessResult.Success -> {
                    config.appendTelegramRemoteLog("收到指令并加入队列 -> ${result.target} (Chat: $chatId)")
                    sendReply(context, chatId, "已接收发信指令 -> ${result.target}", activeInstanceId)
                    UpdateProcessResult.CONSUMED
                }
                is RemoteProcessResult.Duplicate -> {
                    config.appendTelegramRemoteLog("抑制重复指令 (Chat: $chatId)")
                    UpdateProcessResult.IGNORED_PERMANENTLY
                }
                is RemoteProcessResult.Rejected -> {
                    config.appendTelegramRemoteLog("指令被拒绝：${result.detail.ifBlank { result.reason }} (Chat: $chatId)")
                    sendReply(context, chatId, "指令已被拒绝：${result.detail.ifBlank { result.reason }}", activeInstanceId)
                    UpdateProcessResult.IGNORED_PERMANENTLY
                }
                is RemoteProcessResult.Ignored -> {
                    // 非指令普通文本消息，正常永久忽略
                    UpdateProcessResult.IGNORED_PERMANENTLY
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing telegram update", e)
            UpdateProcessResult.RETRYABLE_FAILURE
        }
    }

    companion object {
        private const val TAG = "TelegramRemotePoller"
        private const val PREFS_OFFSET = "tg_remote_offset"

        /**
         * 发送 Telegram 回复，返回真实的 HTTP 执行结果（同步执行，必须在后台线程/Worker中调用）
         */
        fun sendReply(context: Context, chatId: String, text: String, sourceInstanceId: String): Boolean {
            if (chatId.isBlank() || text.isBlank() || sourceInstanceId.isBlank()) return false
            val repo = RemoteSourceRepository.getInstance(context)
            val instance = repo.getSourceById(sourceInstanceId) ?: return false
            val token = instance.optString("botToken").ifBlank { null } ?: return false

            return try {
                val rawHost = instance.optString("customHost").ifBlank { null }
                val baseUrl = rawHost?.trim()?.ifBlank { "https://api.telegram.org" }?.trimEnd('/') ?: "https://api.telegram.org"
                val payload = JSONObject()
                    .put("chat_id", chatId)
                    .put("text", text)
                val request = Request.Builder()
                    .url("$baseUrl/bot$token/sendMessage")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val isOk = response.isSuccessful && JSONObject(body).optBoolean("ok", false)
                    if (!isOk) {
                        Log.w(TAG, "Telegram sendReply failed: HTTP ${response.code}, body: $body")
                    }
                    isOk
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Telegram sendReply error", e)
                false
            }
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
