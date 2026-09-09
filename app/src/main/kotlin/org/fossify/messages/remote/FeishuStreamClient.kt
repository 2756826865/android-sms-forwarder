package org.fossify.messages.remote

import android.util.Log
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.ws.Client
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 飞书官方长连接协议适配器。
 *
 * 飞书长连接不是普通 JSON WebSocket：连接地址由 bootstrap 接口动态下发，消息使用
 * protobuf 二进制帧，并包含握手、心跳、分片与 ACK。底层协议交给官方 SDK，
 * 本类只负责把 im.message.receive_v1 转换成应用内部的远程短信指令。
 */
class FeishuStreamClient(
    private val appId: String,
    private val appSecret: String,
    private val customPrefix: String = "",
    private val onCommand: (FeishuRemoteCommand) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val running = AtomicBoolean(false)

    @Volatile
    private var streamClient: Client? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        Thread {
            try {
                onStatus("正在连接飞书长连接…")
                val dispatcher = EventDispatcher.Builder("", "")
                    .onP2MessageReceiveV1(object : ImService.P2MessageReceiveV1Handler() {
                        override fun handle(event: P2MessageReceiveV1) {
                            handleMessage(event)
                        }
                    })
                    .build()
                val cleanAppId = appId.trim()
                val cleanAppSecret = appSecret.trim()
                val client = Client.Builder(cleanAppId, cleanAppSecret)
                    .eventHandler(dispatcher)
                    .autoReconnect(true)
                    .source("android-sms-forwarder")
                    .onReconnecting { if (running.get()) onStatus("飞书连接已断开，正在重连…") }
                    .onReconnected { if (running.get()) onStatus("已重新连接 · 等待飞书机器人指令") }
                    .build()
                if (!running.get()) {
                    client.close()
                    return@Thread
                }
                streamClient = client
                client.start()
                client.awaitReady(CONNECT_TIMEOUT_MS)
                if (running.get()) {
                    onStatus("已连接 · 等待飞书机器人指令")
                }
            } catch (error: Throwable) {
                // 如果运行状态已被外部主动停止或重载 close，则不作为异常失败抛出给用户
                if (!running.get()) {
                    Log.i(TAG, "Feishu stream client was stopped or reloaded during connection")
                    return@Thread
                }
                Log.e(TAG, "Feishu official stream client failed", error)
                val rawMsg = error.message.orEmpty()
                val tip = when {
                    rawMsg.contains("websocket client closed", ignoreCase = true) -> "连接已重置，正在重新建立长连接…"
                    rawMsg.contains("invalid", ignoreCase = true) || rawMsg.contains("1000040346") -> "App ID 或 Secret 格式无效，请核对飞书后台"
                    else -> "连接失败：${error.message ?: error.javaClass.simpleName}"
                }
                onStatus(tip)
                streamClient?.close()
                streamClient = null
                running.set(false)
            }
        }.apply {
            name = "feishu-official-stream"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        streamClient?.close()
        streamClient = null
    }

    private fun handleMessage(event: P2MessageReceiveV1) {
        if (!running.get()) return
        val data = event.event ?: return
        val message = data.message ?: return
        if (message.messageType != "text") return

        val contentRaw = message.content.orEmpty()
        val textContent = runCatching {
            JSONObject(contentRaw).optString("text")
        }.getOrDefault(contentRaw).trim()
        val messageId = message.messageId.orEmpty()
        if (messageId.isBlank()) {
            onStatus("忽略缺少 message_id 的飞书指令")
            return
        }

        val senderIds = data.sender?.senderId
        val senderId = senderIds?.openId.orEmpty()
            .ifBlank { senderIds?.userId.orEmpty() }
            .ifBlank { senderIds?.unionId.orEmpty() }
        val mentions = message.mentions
        val isMentioned = mentions != null && mentions.isNotEmpty()

        // 剥离飞书消息中的 @ 机器人占位符（如 @_user_1、@机器人名称 等），确保群聊 @ 机器人指令能够正常解析
        var cleanText = textContent
        mentions?.forEach { mention ->
            val key = mention.key.orEmpty()
            if (key.isNotBlank()) {
                cleanText = cleanText.replace("@$key", " ")
            }
            val name = mention.name.orEmpty()
            if (name.isNotBlank()) {
                cleanText = cleanText.replace("@$name", " ")
            }
        }
        // 通用正则清理开头的 @提及 及全半角空格
        cleanText = cleanText.replaceFirst(Regex("^(\\s*@[^\\s]+\\s*)+"), "").trim().trim('　', ' ')

        RemoteSmsCommand.parse(cleanText, customPrefix)?.let { command ->
            onCommand(
                FeishuRemoteCommand(
                    messageId = messageId,
                    command = command,
                    rawContent = cleanText,
                    senderId = senderId,
                    chatId = message.chatId.orEmpty(),
                    chatType = message.chatType.orEmpty(),
                    isMentioned = isMentioned,
                )
            )
        }
    }

    fun sendReply(receiptTarget: String, content: String): Boolean {
        val separator = receiptTarget.indexOf(':')
        if (separator <= 0 || separator == receiptTarget.lastIndex) return false
        val receiveIdType = receiptTarget.substring(0, separator)
        val receiveId = receiptTarget.substring(separator + 1)
        if (receiveIdType != "chat_id" && receiveIdType != "open_id") return false

        val token = runCatching { fetchTenantAccessToken() }.getOrElse {
            Log.e(TAG, "Feishu reply token failed", it)
            return false
        }
        val payload = JSONObject()
            .put("receive_id", receiveId)
            .put("msg_type", "text")
            .put("content", JSONObject().put("text", content).toString())
        val request = Request.Builder()
            .url("$SEND_MESSAGE_URL?receive_id_type=$receiveIdType")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val isOk = response.isSuccessful && runCatching {
                    JSONObject(body).optInt("code", -1) == 0
                }.getOrDefault(false)
                if (!isOk) {
                    Log.e(TAG, "Feishu reply rejected: HTTP ${response.code}, body=$body")
                    onStatus("飞书回复回执失败：HTTP ${response.code} $body")
                }
                isOk
            }
        }.onFailure { Log.e(TAG, "Feishu reply failed", it) }.getOrDefault(false)
    }

    private fun fetchTenantAccessToken(): String {
        val payload = JSONObject()
            .put("app_id", appId)
            .put("app_secret", appSecret)
        val request = Request.Builder()
            .url(AUTH_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful && body.isNotBlank()) {
                "获取 TenantAccessToken 失败 HTTP ${response.code}"
            }
            val json = JSONObject(body)
            check(json.optInt("code", -1) == 0) {
                json.optString("msg", "获取凭证被飞书拒绝")
            }
            return json.optString("tenant_access_token").also {
                check(it.isNotBlank()) { "飞书未返回 tenant_access_token" }
            }
        }
    }

    companion object {
        private const val TAG = "FeishuStreamClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val AUTH_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
        private const val SEND_MESSAGE_URL = "https://open.feishu.cn/open-apis/im/v1/messages"
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}

data class FeishuRemoteCommand(
    val messageId: String,
    val command: RemoteSmsCommand,
    val rawContent: String = "",
    val senderId: String = "",
    val chatId: String = "",
    val chatType: String = "",
    val isMentioned: Boolean = false,
)
