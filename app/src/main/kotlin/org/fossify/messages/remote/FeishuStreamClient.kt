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
                val client = Client.Builder(appId, appSecret)
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
                Log.e(TAG, "Feishu official stream client failed", error)
                if (running.get()) {
                    onStatus("连接失败：${error.message ?: error.javaClass.simpleName}")
                }
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

        RemoteSmsCommand.parse(textContent, customPrefix)?.let { command ->
            onCommand(
                FeishuRemoteCommand(
                    messageId = messageId,
                    command = command,
                    rawContent = textContent,
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
                response.isSuccessful && runCatching {
                    JSONObject(body).optInt("code", -1) == 0
                }.getOrDefault(false)
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
