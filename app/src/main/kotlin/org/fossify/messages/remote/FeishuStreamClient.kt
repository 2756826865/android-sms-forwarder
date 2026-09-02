package org.fossify.messages.remote

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FeishuStreamClient(
    private val appId: String,
    private val appSecret: String,
    private val customPrefix: String = "",
    private val onCommand: (FeishuRemoteCommand) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        connectLoop()
    }

    fun stop() {
        running.set(false)
        webSocket?.close(1000, "client stop")
        webSocket = null
    }

    private fun connectLoop() {
        Thread {
            while (running.get()) {
                try {
                    onStatus("正在连接 Feishu Stream…")
                    openAndListen()
                } catch (error: Throwable) {
                    Log.e(TAG, "Feishu stream connection failed", error)
                    onStatus("连接失败：${error.message ?: error.javaClass.simpleName}")
                }
                if (running.get()) {
                    Thread.sleep(RECONNECT_DELAY_MS)
                }
            }
        }.apply {
            name = "feishu-stream"
            isDaemon = true
            start()
        }
    }

    private fun openAndListen() {
        val token = fetchTenantAccessToken()
        val wsUrl = "wss://open.feishu.cn/ws/v2?client_id=${java.net.URLEncoder.encode(appId, "UTF-8")}"
        val latch = CountDownLatch(1)
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()

        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("已连接 · 等待飞书机器人指令")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("连接已断开：$reason")
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("连接异常：${t.message ?: t.javaClass.simpleName}")
                latch.countDown()
            }
        })
        latch.await()
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
                "获取 TenantAccessToken 失败 HTTP ${response.code}: $body"
            }
            val json = JSONObject(body)
            check(json.optInt("code", -1) == 0) {
                json.optString("msg", "获取凭证被飞书拒绝")
            }
            return json.optString("tenant_access_token")
        }
    }

    private fun handleMessage(webSocket: WebSocket, raw: String) {
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = envelope.optString("type")
        val headers = envelope.optJSONObject("headers") ?: JSONObject()
        val messageId = headers.optString("message_id").ifBlank { headers.optString("messageId") }

        if (type == "ping" || envelope.optString("action") == "ping") {
            reply(webSocket, messageId, JSONObject().put("code", 200).put("msg", "pong"))
            return
        }

        val event = envelope.optJSONObject("event") ?: envelope.optJSONObject("data") ?: envelope
        val message = event.optJSONObject("message")
        if (message != null) {
            val contentRaw = message.optString("content")
            val textContent = runCatching {
                JSONObject(contentRaw).optString("text")
            }.getOrDefault(contentRaw).trim()

            val msgId = message.optString("message_id").ifBlank { messageId }
            RemoteSmsCommand.parse(textContent, customPrefix)?.let { command ->
                if (msgId.isBlank()) {
                    onStatus("忽略缺少 message_id 的飞书指令")
                } else {
                    onCommand(FeishuRemoteCommand(msgId, command))
                }
            }
            reply(webSocket, messageId, JSONObject().put("code", 200).put("msg", "success"))
        }
    }

    private fun reply(webSocket: WebSocket, messageId: String, payload: JSONObject) {
        if (messageId.isBlank()) return
        val response = JSONObject()
            .put("code", 200)
            .put("headers", JSONObject().put("message_id", messageId))
            .put("data", payload)
        webSocket.send(response.toString())
    }

    companion object {
        private const val TAG = "FeishuStreamClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val AUTH_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}

data class FeishuRemoteCommand(
    val messageId: String,
    val command: RemoteSmsCommand,
)