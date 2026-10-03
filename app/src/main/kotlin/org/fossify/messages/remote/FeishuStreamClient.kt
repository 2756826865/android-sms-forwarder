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
    @Volatile
    private var workerThread: Thread? = null
    @Volatile
    private var botOpenId: String? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        Thread {
            var retryDelayMs = INITIAL_RETRY_DELAY_MS
            while (running.get() && workerThread === Thread.currentThread()) {
                try {
                    onStatus("正在连接飞书长连接…")
                    val dispatcher = EventDispatcher.Builder("", "")
                        .onP2MessageReceiveV1(object : ImService.P2MessageReceiveV1Handler() {
                            override fun handle(event: P2MessageReceiveV1) {
                                handleMessage(event)
                            }
                        })
                        .build()
                    val client = Client.Builder(appId.trim(), appSecret.trim())
                        .eventHandler(dispatcher)
                        .autoReconnect(true)
                        .source("android-sms-forwarder")
                        .onReconnecting { if (running.get()) onStatus("飞书连接已断开，正在重连…") }
                        .onReconnected { if (running.get()) onStatus("已重新连接 · 等待飞书机器人指令") }
                        .build()
                    if (!running.get() || workerThread !== Thread.currentThread()) {
                        client.close()
                        break
                    }
                    streamClient = client
                    client.start()
                    client.awaitReady(CONNECT_TIMEOUT_MS)
                    val botIdentityResolved = runCatching { resolveBotOpenId() }
                        .onFailure { Log.w(TAG, "Feishu bot identity resolution failed", it) }
                        .isSuccess
                    if (running.get()) {
                        onStatus(if (botIdentityResolved) {
                            "已连接 · 等待飞书机器人指令"
                        } else {
                            "已连接 · 群聊 @ 身份暂时无法验证，单聊仍可用"
                        })
                    }
                    break
                } catch (error: Throwable) {
                    if (!running.get() || workerThread !== Thread.currentThread()) break
                    Log.e(TAG, "Feishu official stream client failed", error)
                    val invalidCredentials = error.message.orEmpty().contains("1000040346")
                    onStatus(if (invalidCredentials) {
                        "App ID 或 Secret 格式无效，请核对飞书后台"
                    } else {
                        "连接失败，${retryDelayMs / 1000} 秒后重试：${error.javaClass.simpleName}"
                    })
                    streamClient?.close()
                    streamClient = null
                    if (invalidCredentials) {
                        running.set(false)
                        break
                    }
                    try {
                        Thread.sleep(retryDelayMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
        }.apply {
            name = "feishu-official-stream"
            isDaemon = true
            workerThread = this
            start()
        }
    }

    fun stop() {
        running.set(false)
        workerThread?.interrupt()
        workerThread = null
        streamClient?.close()
        streamClient = null
        botOpenId = null
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
        val (senderId, senderIdType) = when {
            !senderIds?.openId.isNullOrBlank() -> senderIds?.openId.orEmpty() to "open_id"
            !senderIds?.userId.isNullOrBlank() -> senderIds?.userId.orEmpty() to "user_id"
            !senderIds?.unionId.isNullOrBlank() -> senderIds?.unionId.orEmpty() to "union_id"
            else -> "" to ""
        }
        val mentions = message.mentions
        val isMentioned = FeishuBotIdentity.isBotMentioned(
            botOpenId,
            mentions?.map { it.id?.openId.orEmpty() }.orEmpty(),
        )

        // mentions.key already includes @ in the official event payload. Only
        // remove leading metadata tokens; mentions in the SMS body are content.
        val cleanText = FeishuMessageText.stripLeadingMentions(
            textContent,
            mentions?.map { it.key.orEmpty() }.orEmpty(),
        )

        RemoteSmsCommand.parse(cleanText, customPrefix)?.let { command ->
            runCatching {
                onCommand(
                    FeishuRemoteCommand(
                        messageId = messageId,
                        command = command,
                        rawContent = cleanText,
                        senderId = senderId,
                        senderIdType = senderIdType,
                        chatId = message.chatId.orEmpty(),
                        chatType = message.chatType.orEmpty(),
                        isMentioned = isMentioned,
                    )
                )
            }.onFailure {
                Log.e(TAG, "Feishu command processing failed for message ${messageId.take(12)}", it)
                onStatus("飞书指令处理异常，请查看应用诊断日志")
            }
        }
    }

    fun sendReply(receiptTarget: String, content: String): Boolean {
        val separator = receiptTarget.indexOf(':')
        if (separator <= 0 || separator == receiptTarget.lastIndex) return false
        val receiveIdType = receiptTarget.substring(0, separator)
        val receiveId = receiptTarget.substring(separator + 1)
        if (receiveIdType !in setOf("chat_id", "open_id", "user_id", "union_id")) return false

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
                    val errorCode = runCatching { JSONObject(body).optInt("code", -1) }.getOrDefault(-1)
                    Log.e(TAG, "Feishu reply rejected: HTTP ${response.code}, code=$errorCode")
                    onStatus("飞书回复回执失败：HTTP ${response.code}，错误码 $errorCode")
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

    private fun resolveBotOpenId() {
        val token = fetchTenantAccessToken()
        val request = Request.Builder()
            .url(BOT_INFO_URL)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful && body.isNotBlank()) {
                "获取飞书机器人信息失败 HTTP ${response.code}"
            }
            botOpenId = FeishuBotIdentity.extractOpenId(body)
        }
    }

    companion object {
        private const val TAG = "FeishuStreamClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val AUTH_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
        private const val BOT_INFO_URL = "https://open.feishu.cn/open-apis/bot/v3/info"
        private const val SEND_MESSAGE_URL = "https://open.feishu.cn/open-apis/im/v1/messages"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val INITIAL_RETRY_DELAY_MS = 5_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
    }
}

data class FeishuRemoteCommand(
    val messageId: String,
    val command: RemoteSmsCommand,
    val rawContent: String = "",
    val senderId: String = "",
    val senderIdType: String = "",
    val chatId: String = "",
    val chatType: String = "",
    val isMentioned: Boolean = false,
)
