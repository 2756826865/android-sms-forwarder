@file:Suppress("DEPRECATION")

package org.fossify.messages.remote

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.remote.repository.RemoteSourceConnectionState
import org.fossify.messages.remote.repository.RemoteSourceRepository
import org.fossify.messages.remote.repository.RemoteSourceType
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketRemoteClient(
    context: Context,
    private val sourceInstanceId: String? = null,
    private val onStatus: (String) -> Unit,
) {
    private val context: Context = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private val running = AtomicBoolean(false)
    private val isAuthenticated = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val instanceId = sourceInstanceId.orEmpty()
        if (instanceId.isNotBlank()) {
            activeClients[instanceId] = this
        }

        Thread {
            var retryDelayMs = 5_000L
            while (running.get()) {
                isAuthenticated.set(false)
                val repo = RemoteSourceRepository.getInstance(context)
                val instance = sourceInstanceId?.let { repo.getSourceById(it) }
                if (instance == null || !instance.enabled) {
                    Thread.sleep(5_000)
                    continue
                }

                try {
                    val url = instance.optString("url")
                    val token = instance.optString("token")
                    if (url.isBlank()) {
                        onStatus("缺少 WebSocket 服务器 URL")
                        repo.updateConnectionState(instance.id, RemoteSourceConnectionState.CONFIG_REQUIRED)
                        Thread.sleep(10_000)
                        continue
                    }
                    onStatus("正在连接 WebSocket…")
                    retryDelayMs = 5_000L
                    connectAndListen(url, token, instance.id)
                } catch (e: Throwable) {
                    Log.e(TAG, "WS connect error", e)
                    val err = "连接断开：${e.message ?: e.javaClass.simpleName}"
                    onStatus(err)
                    isAuthenticated.set(false)
                    repo.updateConnectionState(instance.id, RemoteSourceConnectionState.ERROR, errorMessage = err)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
                }
                if (running.get()) {
                    try {
                        Thread.sleep(retryDelayMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.apply {
            name = "ws-remote-client-${sourceInstanceId ?: "def"}"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        isAuthenticated.set(false)
        webSocket?.close(1000, "Client stopped")
        webSocket = null
        sourceInstanceId?.let { activeClients.remove(it) }
    }

    private fun connectAndListen(url: String, token: String, activeInstanceId: String) {
        val latch = CountDownLatch(1)
        val requestBuilder = Request.Builder().url(url)
        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
            requestBuilder.header("X-Token", token)
        }

        val repo = RemoteSourceRepository.getInstance(context)
        webSocket = http.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // HTTP Upgrade 成功即代表握手层（含可选 Bearer/X-Token）已被服务端接受。
                // 同时发送兼容性的应用层 auth 消息；服务端若随后明确 auth_fail 仍会撤销认证。
                isAuthenticated.set(true)
                onStatus(if (token.isBlank()) "WebSocket 已连接 · 无鉴权模式" else "WebSocket 已连接 · Token 握手成功")
                repo.updateConnectionState(activeInstanceId, RemoteSourceConnectionState.READY)
                if (token.isNotBlank()) {
                    val authMsg = JSONObject().put("action", "auth").put("token", token)
                    webSocket.send(authMsg.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingJson(text, activeInstanceId)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isAuthenticated.set(false)
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isAuthenticated.set(false)
                onStatus("连接已关闭：$reason")
                if (activeInstanceId.isNotBlank()) {
                    repo.updateConnectionState(activeInstanceId, RemoteSourceConnectionState.ERROR, errorMessage = "已关闭: $reason")
                }
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isAuthenticated.set(false)
                val err = "连接异常：${t.message ?: t.javaClass.simpleName}"
                onStatus(err)
                if (activeInstanceId.isNotBlank()) {
                    repo.updateConnectionState(activeInstanceId, RemoteSourceConnectionState.ERROR, errorMessage = err)
                }
                latch.countDown()
            }
        })
        latch.await()
    }

    private fun handleIncomingJson(text: String, activeInstanceId: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val action = json.optString("action")
        val repo = RemoteSourceRepository.getInstance(context)
        if (action == "ping") {
            webSocket?.send(JSONObject().put("action", "pong").toString())
            return
        }
        if (action == "auth_ok" || action == "auth_success") {
            isAuthenticated.set(true)
            onStatus("WebSocket 认证成功 · 就绪")
            if (activeInstanceId.isNotBlank()) {
                repo.updateConnectionState(activeInstanceId, RemoteSourceConnectionState.READY)
            }
            return
        }
        if (action == "auth_fail" || action == "auth_error") {
            isAuthenticated.set(false)
            val err = "WebSocket 认证失败：${json.optString("message", "Token 无效")}"
            onStatus(err)
            if (activeInstanceId.isNotBlank()) {
                repo.updateConnectionState(activeInstanceId, RemoteSourceConnectionState.ERROR, errorMessage = err)
            }
            return
        }
        if (action != "send_sms" && action != "sendSms" && action != "remote_sms") return

        val commandId = json.optString("commandId").ifBlank { json.optString("id") }.trim()

        // 严格认证门禁
        if (!isAuthenticated.get()) {
            onStatus("未认证状态下收到指令，已拒绝")
            if (commandId.isNotBlank()) {
                webSocket?.send(
                    JSONObject().put("action", "send_result")
                        .put("commandId", commandId)
                        .put("status", "rejected")
                        .put("reason", "UNAUTHENTICATED")
                        .toString()
                )
            }
            return
        }

        // 强制唯一 commandId 校验
        if (commandId.isBlank()) {
            onStatus("拒绝无效指令：缺少必需的唯一 commandId")
            webSocket?.send(
                JSONObject().put("action", "send_result")
                    .put("status", "rejected")
                    .put("reason", "COMMAND_ID_REQUIRED")
                    .toString()
            )
            return
        }

        // 防重放与时间戳严格校验 (强制要求 timestamp 且偏差在 10 分钟以内)
        val timestamp = json.optLong("timestamp", 0L)
        if (timestamp <= 0L || Math.abs(System.currentTimeMillis() - timestamp) > 10 * 60 * 1000L) {
            onStatus("拒绝无效指令：时间戳缺失或超出10分钟窗口 (commandId: $commandId)")
            webSocket?.send(
                JSONObject().put("action", "send_result")
                    .put("commandId", commandId)
                    .put("status", "rejected")
                    .put("reason", "TIMESTAMP_INVALID_OR_EXPIRED")
                    .toString()
            )
            return
        }

        val target = json.optString("target").ifBlank { json.optString("phone") }.trim()
        val content = json.optString("content").ifBlank { json.optString("msg") }.trim()
        val simSlotStr = json.optString("simSlot").ifBlank { json.optString("sim") }.trim()
        val senderId = json.optString("senderId")
            .ifBlank { json.optString("user") }
            .ifBlank { json.optString("from") }
            .ifBlank { "websocket-server" }
            .trim()

        if (target.isBlank() || content.isBlank()) {
            onStatus("忽略无效的 WebSocket 指令载荷")
            webSocket?.send(
                JSONObject().put("action", "send_result")
                    .put("commandId", commandId)
                    .put("status", "rejected")
                    .put("reason", "EMPTY_PAYLOAD")
                    .toString()
            )
            return
        }

        val instance = if (activeInstanceId.isNotBlank()) repo.getSourceById(activeInstanceId) else null
        val prefix = instance?.customCommandPrefix?.ifBlank { "/发信" } ?: "/发信"
        val simPrefix = if (simSlotStr.isNotBlank()) "$simSlotStr " else ""
        val rawCommandText = "$prefix $simPrefix$target $content".trim()

        val envelope = RemoteCommandEnvelope(
            sourceType = RemoteSourceType.WEBSOCKET,
            sourceInstanceId = activeInstanceId,
            sourceMessageKey = commandId,
            senderId = senderId,
            rawContent = rawCommandText,
            receivedAt = timestamp
        )

        val config = MultiForwardConfig(context)
        when (val result = RemoteCommandProcessor.process(context, envelope)) {
            is RemoteProcessResult.Success -> {
                config.appendWebSocketRemoteLog("收到指令并加入队列 -> $target")
                webSocket?.send(JSONObject().put("action", "send_result").put("commandId", commandId).put("status", "queued").toString())
            }
            is RemoteProcessResult.Duplicate -> {
                config.appendWebSocketRemoteLog("抑制重复指令 -> $target")
                webSocket?.send(JSONObject().put("action", "send_result").put("commandId", commandId).put("status", "duplicate").toString())
            }
            is RemoteProcessResult.Rejected -> {
                config.appendWebSocketRemoteLog("指令被拒绝：${result.detail.ifBlank { result.reason }}")
                webSocket?.send(JSONObject().put("action", "send_result").put("commandId", commandId).put("status", "rejected").put("reason", result.reason).toString())
            }
            is RemoteProcessResult.Ignored -> {}
        }
    }

    fun sendReceipt(commandId: String, status: String, detail: String = ""): Boolean {
        if (!isAuthenticated.get()) return false
        val ws = webSocket ?: return false
        val payload = JSONObject()
            .put("action", "receipt")
            .put("commandId", commandId)
            .put("status", status)
            .put("detail", detail)
            .put("timestamp", System.currentTimeMillis())
        return ws.send(payload.toString())
    }

    companion object {
        private const val TAG = "WebSocketRemoteClient"
        private val activeClients = ConcurrentHashMap<String, WebSocketRemoteClient>()

        fun sendReceiptOverSocket(commandId: String, status: String, detail: String = "", sourceInstanceId: String = ""): Boolean {
            if (sourceInstanceId.isBlank()) return false
            val client = activeClients[sourceInstanceId] ?: return false
            return client.sendReceipt(commandId, status, detail)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
