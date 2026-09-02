@file:Suppress("DEPRECATION")

package org.fossify.messages.remote

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketRemoteClient(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        activeInstance = this
        Thread {
            while (running.get()) {
                try {
                    val config = MultiForwardConfig(context)
                    val url = config.websocketRemoteUrl()
                    if (url.isBlank()) {
                        onStatus("缺少 WebSocket 服务器 URL")
                        Thread.sleep(10_000)
                        continue
                    }
                    onStatus("正在连接 WebSocket…")
                    connectAndListen(url, config.websocketRemoteToken())
                } catch (e: Throwable) {
                    Log.e(TAG, "WS connect error", e)
                    onStatus("连接断开：${e.message ?: e.javaClass.simpleName}")
                }
                if (running.get()) {
                    Thread.sleep(5_000)
                }
            }
        }.apply {
            name = "ws-remote-client"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        if (activeInstance === this) activeInstance = null
        webSocket?.close(1000, "client stop")
        webSocket = null
    }

    private fun connectAndListen(url: String, token: String) {
        val latch = CountDownLatch(1)
        val requestBuilder = Request.Builder().url(url)
        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
            requestBuilder.header("X-Token", token)
        }

        webSocket = http.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("WebSocket 已连接 · 就绪")
                if (token.isNotBlank()) {
                    val authMsg = JSONObject().put("action", "auth").put("token", token)
                    webSocket.send(authMsg.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingJson(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("连接已关闭：$reason")
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("连接异常：${t.message ?: t.javaClass.simpleName}")
                latch.countDown()
            }
        })
        latch.await()
    }

    private fun handleIncomingJson(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val action = json.optString("action")
        if (action == "ping") {
            webSocket?.send(JSONObject().put("action", "pong").toString())
            return
        }
        if (action != "send_sms" && action != "sendSms" && action != "remote_sms") return

        val commandId = json.optString("commandId").ifBlank { json.optString("id") }
        val target = json.optString("target").ifBlank { json.optString("phone") }
        val content = json.optString("content").ifBlank { json.optString("msg") }
        val simSlotStr = json.optString("simSlot").ifBlank { json.optString("sim") }

        if (target.isBlank() || content.isBlank()) {
            onStatus("忽略无效的 WebSocket 指令载荷")
            return
        }

        val config = MultiForwardConfig(context)
        val parsedCmd = RemoteSmsCommand.parse("/发信 $simSlotStr $target $content".trim())
            ?: RemoteSmsCommand(target, content, SimSendResolver.MODE_DEFAULT)
        val sendMode = parsedCmd.effectiveSendMode(config.websocketRemoteSendSimMode)
        val messageKey = commandId.ifBlank { "ws-$target-${content.hashCode()}" }

        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.WEBSOCKET,
            sourceMessageKey = messageKey,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = target,
            rawPayload = content,
            requestedSimMode = sendMode,
            rawRequester = "websocket-server",
            receivedAt = System.currentTimeMillis(),
        )

        val claimResult = runBlocking {
            RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        }

        if (claimResult is RemoteCommandRepository.ClaimResult.Duplicate) {
            config.appendWebSocketRemoteLog("抑制重复指令 -> $target")
            return
        }

        val finalCmdId = (claimResult as? RemoteCommandRepository.ClaimResult.NewCommand)?.commandId.orEmpty()
        val fingerprint = "ws-${sha256(messageKey)}"
        val simSuffix = " · ${SimSendResolver.describeForLog(context, null, sendMode)}"
        config.appendWebSocketRemoteLog("收到指令 -> $target$simSuffix")

        val rulesConfig = ForwardingRulesConfig(context)
        if (rulesConfig.affectsRemoteCommands() && rulesConfig.rules.any { it.enabled }) {
            val decision = ForwardingRuleEngine(rulesConfig.rules).evaluate(
                sender = SOURCE_WEBSOCKET,
                body = "$target $content",
                subscriptionId = -1,
                channelCandidates = emptySet(),
                simSlotIndex = null,
            )
            if (decision.matchedRules.isEmpty()) {
                if (finalCmdId.isNotBlank()) {
                    RemoteCommandRepository.recordAuthorization(context, finalCmdId, authorized = false, reason = "RULE_BLOCKED")
                }
                config.appendWebSocketRemoteLog("规则阻止执行 -> $target$simSuffix")
                return
            }
        }

        if (finalCmdId.isNotBlank()) {
            RemoteCommandRepository.recordAuthorization(context, finalCmdId, authorized = true, reason = "WS_AUTHORIZED")
        }

        RemoteSmsCommandWorker.enqueue(
            context = context,
            target = target,
            content = content,
            subId = -1,
            uniqueId = fingerprint,
            sendMode = sendMode,
            source = SOURCE_WEBSOCKET,
            commandId = finalCmdId,
        )
    }

    companion object {
        private const val TAG = "WebSocketRemoteClient"
        private var activeInstance: WebSocketRemoteClient? = null

        fun sendReceiptOverSocket(commandId: String, status: String, detail: String = "") {
            val ws = activeInstance?.webSocket ?: return
            val payload = JSONObject()
                .put("action", "receipt")
                .put("commandId", commandId)
                .put("status", status)
                .put("detail", detail)
                .put("timestamp", System.currentTimeMillis())
            ws.send(payload.toString())
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
