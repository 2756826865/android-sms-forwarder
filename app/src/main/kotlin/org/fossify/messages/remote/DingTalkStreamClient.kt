package org.fossify.messages.remote

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DingTalkStreamClient(
    private val clientId: String,
    private val clientSecret: String,
    private val onCommand: (RemoteSmsCommand) -> Unit,
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
                    onStatus("正在连接 DingTalk Stream…")
                    openAndListen()
                } catch (error: Throwable) {
                    Log.e(TAG, "DingTalk stream connection failed", error)
                    onStatus("连接失败：${error.message ?: error.javaClass.simpleName}")
                }
                if (running.get()) {
                    Thread.sleep(RECONNECT_DELAY_MS)
                }
            }
        }.apply {
            name = "dingtalk-stream"
            isDaemon = true
            start()
        }
    }

    private fun openAndListen() {
        val openResult = openConnection()
        val endpoint = openResult.getString("endpoint")
        val ticket = openResult.getString("ticket")
        val wsUrl = buildWebSocketUrl(endpoint, ticket)
        val latch = java.util.concurrent.CountDownLatch(1)
        val request = Request.Builder().url(wsUrl).build()
        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("已连接 · 等待机器人消息")
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

    private fun openConnection(): JSONObject {
        val payload = JSONObject()
            .put("clientId", clientId)
            .put("clientSecret", clientSecret)
            .put(
                "subscriptions",
                JSONArray().put(
                    JSONObject()
                        .put("topic", BOT_MESSAGE_TOPIC)
                        .put("type", "CALLBACK"),
                ),
            )
            .put("ua", "android-sms-forwarder/1.0.4")
        val request = Request.Builder()
            .url(OPEN_CONNECTION_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful && body.isNotBlank()) {
                "注册连接失败 HTTP ${response.code}: $body"
            }
            return JSONObject(body)
        }
    }

    private fun handleMessage(webSocket: WebSocket, raw: String) {
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = envelope.optString("type")
        val headers = envelope.optJSONObject("headers") ?: JSONObject()
        val topic = headers.optString("topic")
        val messageId = headers.optString("messageId")

        when {
            type == "SYSTEM" && topic == "ping" -> {
                val opaque = runCatching {
                    JSONObject(envelope.optString("data")).optString("opaque")
                }.getOrDefault("")
                reply(webSocket, messageId, JSONObject().put("opaque", opaque))
            }
            type == "SYSTEM" && topic == "disconnect" -> {
                onStatus("服务端请求重连")
                webSocket.close(1000, "server disconnect")
            }
            type == "CALLBACK" && topic == BOT_MESSAGE_TOPIC -> {
                val dataText = envelope.optString("data")
                val content = runCatching {
                    JSONObject(dataText).optJSONObject("text")?.optString("content").orEmpty()
                }.getOrDefault("").trim()
                RemoteSmsCommand.parse(content)?.let(onCommand)
                reply(webSocket, messageId, JSONObject().put("response", JSONObject.NULL))
            }
        }
    }

    private fun reply(webSocket: WebSocket, messageId: String, payload: JSONObject) {
        val response = JSONObject()
            .put("code", 200)
            .put("message", "OK")
            .put(
                "headers",
                JSONObject()
                    .put("messageId", messageId)
                    .put("contentType", "application/json"),
            )
            .put("data", payload.toString())
        webSocket.send(response.toString())
    }

    private fun buildWebSocketUrl(endpoint: String, ticket: String): String {
        val separator = if (endpoint.contains('?')) '&' else '?'
        return "$endpoint${separator}ticket=${java.net.URLEncoder.encode(ticket, Charsets.UTF_8.name())}"
    }

    companion object {
        private const val TAG = "DingTalkStreamClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val OPEN_CONNECTION_URL = "https://api.dingtalk.com/v1.0/gateway/connections/open"
        private const val BOT_MESSAGE_TOPIC = "/v1.0/im/bot/messages/get"
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
