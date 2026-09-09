package org.fossify.messages.remote

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 企业微信官方智能机器人 WebSocket 长连接客户端。
 *
 * 协议严格以 WeCom 官方开源 SDK (aibot-node-sdk / wecom-aibot-python-sdk) 源码为唯一依据：
 * 1. 默认 WebSocket URL: wss://openws.work.weixin.qq.com
 * 2. 鉴权帧:
 *    cmd: "aibot_subscribe"
 *    headers: { "req_id": "aibot_subscribe_..." }
 *    body: { "bot_id": "...", "secret": "..." }
 * 3. 心跳帧:
 *    cmd: "ping"
 *    headers: { "req_id": "ping_..." }
 *    body: {}
 *    间隔 30 秒，未收到 pong 计数累加，>= 2 时触发重连。
 * 4. 消息回调帧:
 *    cmd: "aibot_msg_callback"
 *    headers: { "req_id": "..." }
 *    body: {
 *       "msgid": "...",
 *       "chattype": "single" | "group",
 *       "chatid": "...",
 *       "from": { "userid": "..." },
 *       "msgtype": "text",
 *       "text": { "content": "..." }
 *    }
 * 5. 被动回复:
 *    cmd: "aibot_respond_msg"
 *    headers: { "req_id": <回调中收到的原始 req_id> }
 *    body: {
 *       "msgtype": "stream",
 *       "stream": { "id": "...", "finish": true, "content": "..." }
 *    }
 * 6. 主动推送:
 *    cmd: "aibot_send_msg"
 *    headers: { "req_id": "aibot_send_msg_..." }
 *    body: {
 *       "chatid": "...",
 *       "msgtype": "markdown",
 *       "markdown": { "content": "..." }
 *    }
 * 7. 事件帧 (服务端踢线通知):
 *    cmd: "aibot_event_callback"
 *    body: { "event": { "eventtype": "disconnected_event" } }
 *    当相同 bot_id 建立新连接时触发，此时必须停止重连并标记下线。
 */
class WeComStreamClient(
    private val botId: String,
    private val secret: String,
    private val customPrefix: String = "",
    private val onCommand: (WeComRemoteCommand) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val isAuthenticated = AtomicBoolean(false)
    private val isKickedOff = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null

    private var heartbeatScheduler: ScheduledExecutorService? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private val missedPongCount = AtomicInteger(0)

    // 串行消息发送执行器，确保回复与推送按序发送，避免重叠冲突
    private val sendExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wecom-sender-${botId.take(6)}").apply { isDaemon = true }
    }

    // 等待 ACK 响应的暂存表，Key 为 req_id
    private val pendingAcks = ConcurrentHashMap<String, PendingAck>()

    private class PendingAck(
        val latch: CountDownLatch = CountDownLatch(1),
        var responseJson: JSONObject? = null
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        isKickedOff.set(false)
        connectLoop()
    }

    fun stop() {
        running.set(false)
        isAuthenticated.set(false)
        stopHeartbeat()
        pendingAcks.clear()
        webSocket?.close(1000, "Client stopped")
        webSocket = null
    }

    private fun connectLoop() {
        Thread {
            var retryDelayMs = 5_000L
            while (running.get() && !isKickedOff.get()) {
                isAuthenticated.set(false)
                try {
                    onStatus("正在连接企业微信长连接…")
                    connectAndListen()
                    retryDelayMs = 5_000L
                } catch (error: Throwable) {
                    Log.e(TAG, "WeCom connection error", error)
                    if (running.get() && !isKickedOff.get()) {
                        val msg = error.message ?: error.javaClass.simpleName
                        onStatus("连接断开：$msg")
                    }
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
                }

                if (running.get() && !isKickedOff.get()) {
                    try {
                        Thread.sleep(retryDelayMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.apply {
            name = "wecom-stream-${botId.take(6)}"
            isDaemon = true
            start()
        }
    }

    private fun connectAndListen() {
        stopHeartbeat()
        webSocket?.close(1000, "Reconnecting")
        webSocket = null

        val request = Request.Builder().url(WS_URL).build()
        val closeLatch = CountDownLatch(1)

        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (!running.get() || isKickedOff.get()) {
                    ws.close(1000, "Closed")
                    return
                }
                onStatus("WebSocket 已连接，正在鉴权…")
                sendSubscribeAuth(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (!running.get()) return
                handleFrame(ws, text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                closeLatch.countDown()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                closeLatch.countDown()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                closeLatch.countDown()
            }
        })

        try {
            closeLatch.await()
        } catch (_: InterruptedException) {
        } finally {
            stopHeartbeat()
            isAuthenticated.set(false)
        }
    }

    private fun sendSubscribeAuth(ws: WebSocket) {
        val reqId = "aibot_subscribe_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val authFrame = JSONObject().apply {
            put("cmd", "aibot_subscribe")
            put("headers", JSONObject().put("req_id", reqId))
            put("body", JSONObject().apply {
                put("bot_id", botId)
                put("secret", secret)
            })
        }
        ws.send(authFrame.toString())
    }

    private fun handleFrame(ws: WebSocket, raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val cmd = json.optString("cmd")
        val headers = json.optJSONObject("headers") ?: JSONObject()
        val reqId = headers.optString("req_id")
        val body = json.optJSONObject("body") ?: JSONObject()

        // 1. 若此 reqId 在待 ACK 表中，则唤醒等待线程
        if (reqId.isNotBlank()) {
            val pending = pendingAcks.remove(reqId)
            if (pending != null) {
                pending.responseJson = json
                pending.latch.countDown()
            }
        }

        when (cmd) {
            // 鉴权响应 / 心跳 pong / 通用响应 (errcode 属于根节点或 body 节点)
            "", "pong", "aibot_subscribe_resp", "ping_resp" -> {
                val errCode = if (json.has("errcode")) json.optInt("errcode") else body.optInt("errcode", 0)
                val errMsg = if (json.has("errmsg")) json.optString("errmsg") else body.optString("errmsg")

                if (reqId.startsWith("aibot_subscribe_")) {
                    if (errCode == 0) {
                        isAuthenticated.set(true)
                        onStatus("已就绪 · 等待企业微信机器人指令")
                        startHeartbeat(ws)
                    } else {
                        isAuthenticated.set(false)
                        val err = "鉴权失败：$errMsg (code=$errCode)"
                        onStatus(err)
                        Log.e(TAG, "WeCom subscribe failed: code=$errCode msg=$errMsg")
                        // 凭据错误则断开重连
                        ws.close(1000, "Auth failed")
                    }
                } else if (reqId.startsWith("ping_") || cmd == "pong") {
                    missedPongCount.set(0)
                }
            }

            // 心跳响应单独处理
            "pong" -> {
                missedPongCount.set(0)
            }

            // 消息回调
            "aibot_msg_callback" -> {
                handleMessageCallback(headers, body)
            }

            // 事件回调（如踢线事件）
            "aibot_event_callback" -> {
                val event = body.optJSONObject("event") ?: JSONObject()
                val eventType = event.optString("eventtype")
                if (eventType == "disconnected_event") {
                    isKickedOff.set(true)
                    val tip = "已在其他地方建立长连接，当前连接已自动断开（避免冲突，停止自动重连）"
                    onStatus(tip)
                    Log.w(TAG, tip)
                    stop()
                }
            }

            else -> {
                // 检查是否为带有 errcode 的响应
                if (json.has("errcode") || body.has("errcode")) {
                    val code = if (json.has("errcode")) json.optInt("errcode") else body.optInt("errcode")
                    if (reqId.startsWith("ping_")) {
                        missedPongCount.set(0)
                    }
                    if (code != 0) {
                        Log.w(TAG, "WeCom received error frame for req_id=$reqId, code=$code")
                    }
                }
            }
        }
    }

    private fun handleMessageCallback(headers: JSONObject, body: JSONObject) {
        val reqId = headers.optString("req_id")
        val msgId = body.optString("msgid")
        val chatType = body.optString("chattype") // "single" | "group"
        val chatId = body.optString("chatid")
        val fromObj = body.optJSONObject("from") ?: JSONObject()
        val senderId = fromObj.optString("userid")
        val msgType = body.optString("msgtype")

        if (msgType != "text") {
            return
        }

        val textObj = body.optJSONObject("text") ?: JSONObject()
        val rawContent = textObj.optString("content").trim()

        // 去掉企微群聊中自动附加的前导 @机器人昵称（如 "@智能机器人 /短信发送 ..."）
        val cleanContent = cleanAtPrefix(rawContent)

        val command = RemoteSmsCommand.parse(cleanContent, customPrefix)
        if (command != null) {
            onCommand(
                WeComRemoteCommand(
                    reqId = reqId,
                    msgId = msgId.ifBlank { reqId },
                    chatType = chatType,
                    chatId = chatId,
                    senderId = senderId,
                    rawContent = cleanContent,
                    isMentioned = chatType == "group", // 群聊中被 @ 触发长连接回调
                    command = command
                )
            )
        }
    }

    private fun cleanAtPrefix(text: String): String {
        var result = text.trim()
        if (result.startsWith("@")) {
            val spaceIndex = result.indexOf(' ')
            if (spaceIndex != -1) {
                result = result.substring(spaceIndex + 1).trim()
            }
        }
        return result
    }

    private fun startHeartbeat(ws: WebSocket) {
        stopHeartbeat()
        missedPongCount.set(0)
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "wecom-heartbeat-${botId.take(6)}").apply { isDaemon = true }
        }.also { executor ->
            heartbeatFuture = executor.scheduleWithFixedDelay({
                if (!running.get() || !isAuthenticated.get()) return@scheduleWithFixedDelay

                if (missedPongCount.get() >= 2) {
                    Log.w(TAG, "WeCom missed >= 2 pongs, reconnecting...")
                    ws.close(1000, "Heartbeat timeout")
                    return@scheduleWithFixedDelay
                }

                try {
                    val reqId = "ping_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
                    val pingFrame = JSONObject().apply {
                        put("cmd", "ping")
                        put("headers", JSONObject().put("req_id", reqId))
                        put("body", JSONObject())
                    }
                    missedPongCount.incrementAndGet()
                    ws.send(pingFrame.toString())
                } catch (e: Throwable) {
                    Log.e(TAG, "Heartbeat send error", e)
                }
            }, 30, 30, TimeUnit.SECONDS)
        }
    }

    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(true)
        heartbeatFuture = null
        heartbeatScheduler?.shutdownNow()
        heartbeatScheduler = null
    }

    /**
     * 原路回复被动响应。
     * 官方协议：cmd = "aibot_respond_msg"，headers = { req_id: <回调的req_id> }，body = stream 类型
     */
    fun sendReply(callbackReqId: String, content: String): Boolean {
        if (!isAuthenticated.get()) return false
        val ws = webSocket ?: return false

        return try {
            sendExecutor.submit<Boolean> {
                val streamId = "stream_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
                val replyFrame = JSONObject().apply {
                    put("cmd", "aibot_respond_msg")
                    put("headers", JSONObject().put("req_id", callbackReqId))
                    put("body", JSONObject().apply {
                        put("msgtype", "stream")
                        put("stream", JSONObject().apply {
                            put("id", streamId)
                            put("finish", true)
                            put("content", content)
                        })
                    })
                }

                val ack = PendingAck()
                pendingAcks[callbackReqId] = ack
                val sent = ws.send(replyFrame.toString())
                if (!sent) {
                    pendingAcks.remove(callbackReqId)
                    return@submit false
                }

                val ok = ack.latch.await(5, TimeUnit.SECONDS)
                pendingAcks.remove(callbackReqId)
                if (ok && ack.responseJson != null) {
                    val res = ack.responseJson!!
                    val errCode = if (res.has("errcode")) res.optInt("errcode") else res.optJSONObject("body")?.optInt("errcode", 0) ?: 0
                    errCode == 0
                } else {
                    sent
                }
            }.get(7, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            Log.e(TAG, "sendReply error", e)
            false
        }
    }

    /**
     * 主动向群聊或私聊推送消息。
     * 官方协议：cmd = "aibot_send_msg"，headers = { req_id }，body = { chatid, msgtype: "markdown", markdown: { content } }
     */
    fun push(chatId: String, content: String): Boolean {
        if (!isAuthenticated.get()) return false
        val ws = webSocket ?: return false

        return try {
            sendExecutor.submit<Boolean> {
                val reqId = "aibot_send_msg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                val pushFrame = JSONObject().apply {
                    put("cmd", "aibot_send_msg")
                    put("headers", JSONObject().put("req_id", reqId))
                    put("body", JSONObject().apply {
                        put("chatid", chatId)
                        put("msgtype", "markdown")
                        put("markdown", JSONObject().put("content", content))
                    })
                }

                val ack = PendingAck()
                pendingAcks[reqId] = ack
                val sent = ws.send(pushFrame.toString())
                if (!sent) {
                    pendingAcks.remove(reqId)
                    return@submit false
                }

                val ok = ack.latch.await(5, TimeUnit.SECONDS)
                pendingAcks.remove(reqId)
                if (ok && ack.responseJson != null) {
                    val res = ack.responseJson!!
                    val errCode = if (res.has("errcode")) res.optInt("errcode") else res.optJSONObject("body")?.optInt("errcode", 0) ?: 0
                    errCode == 0
                } else {
                    sent
                }
            }.get(7, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            Log.e(TAG, "push error", e)
            false
        }
    }

    companion object {
        private const val TAG = "WeComStreamClient"
        private const val WS_URL = "wss://openws.work.weixin.qq.com"
    }
}

data class WeComRemoteCommand(
    val reqId: String,
    val msgId: String,
    val chatType: String,
    val chatId: String,
    val senderId: String,
    val rawContent: String,
    val isMentioned: Boolean,
    val command: RemoteSmsCommand,
)