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

class QqRemoteClient(
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
                    val url = config.qqRemoteWsUrl()
                    if (url.isBlank()) {
                        onStatus("缺少 OneBot 11 WebSocket URL")
                        Thread.sleep(10_000)
                        continue
                    }
                    onStatus("正在连接 OneBot 11 QQ 客户端…")
                    connectAndListen(url, config.qqRemoteToken())
                } catch (e: Throwable) {
                    Log.e(TAG, "QQ connect error", e)
                    onStatus("连接断开：${e.message ?: e.javaClass.simpleName}")
                }
                if (running.get()) {
                    Thread.sleep(5_000)
                }
            }
        }.apply {
            name = "qq-remote-client"
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
        }

        webSocket = http.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("OneBot 11 已连接 · 等待 QQ 指令")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleOneBotEvent(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("QQ 连接已关闭：$reason")
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("QQ 连接异常：${t.message ?: t.javaClass.simpleName}")
                latch.countDown()
            }
        })
        latch.await()
    }

    private fun handleOneBotEvent(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val postType = json.optString("post_type")
        if (postType != "message") return

        val messageType = json.optString("message_type") // private or group
        val userId = json.optLong("user_id", 0L).toString()
        val groupId = json.optLong("group_id", 0L).toString()
        val rawMessage = json.optString("raw_message").ifBlank { json.optString("message") }
        val messageId = json.optLong("message_id", 0L).toString()

        val config = MultiForwardConfig(context)
        val authUsers = config.qqRemoteAuthorizedUsers().split('\n', ',', ';', '，', '；')
            .map(String::trim).filter(String::isNotBlank)
        val authGroups = config.qqRemoteAuthorizedGroups().split('\n', ',', ';', '，', '；')
            .map(String::trim).filter(String::isNotBlank)

        if (authUsers.isNotEmpty() && !authUsers.contains(userId)) {
            config.appendQqRemoteLog("忽略未授权 QQ 用户 [$userId]")
            return
        }

        if (messageType == "group") {
            if (authGroups.isNotEmpty() && !authGroups.contains(groupId)) {
                config.appendQqRemoteLog("忽略未授权 QQ 群 [$groupId]")
                return
            }
            if (config.qqRemoteRequireAt && !rawMessage.contains("[CQ:at") && !rawMessage.contains("@")) {
                return
            }
        }

        val cleanText = rawMessage.replace("\\[CQ:.*?\\]".toRegex(), "").trim()
        val command = RemoteSmsCommand.parse(cleanText, config.qqRemoteCustomPrefix()) ?: return
        val messageKey = "qq-$messageType-$groupId-$userId-$messageId"
        val sendMode = command.effectiveSendMode(config.qqRemoteSendSimMode)
        val requester = if (messageType == "group") "qq_group:$groupId:$userId" else "qq_user:$userId"

        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.QQ,
            sourceMessageKey = messageKey,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = command.targetNumber,
            rawPayload = command.content,
            requestedSimMode = sendMode,
            rawRequester = requester,
            receivedAt = System.currentTimeMillis(),
        )

        val claimResult = runBlocking {
            RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        }

        if (claimResult is RemoteCommandRepository.ClaimResult.Duplicate) {
            config.appendQqRemoteLog("抑制重复指令 -> ${command.targetNumber}")
            return
        }

        val finalCmdId = (claimResult as? RemoteCommandRepository.ClaimResult.NewCommand)?.commandId.orEmpty()
        val fingerprint = "qq-${sha256(messageKey)}"
        val simSuffix = " · ${SimSendResolver.describeForLog(context, null, sendMode)}"
        config.appendQqRemoteLog("收到指令 -> ${command.targetNumber}$simSuffix (来自 QQ: $userId)")

        val rulesConfig = ForwardingRulesConfig(context)
        if (rulesConfig.affectsRemoteCommands() && rulesConfig.rules.any { it.enabled }) {
            val decision = ForwardingRuleEngine(rulesConfig.rules).evaluate(
                sender = SOURCE_QQ,
                body = "${command.targetNumber} ${command.content}",
                subscriptionId = -1,
                channelCandidates = emptySet(),
                simSlotIndex = null,
            )
            if (decision.matchedRules.isEmpty()) {
                if (finalCmdId.isNotBlank()) {
                    RemoteCommandRepository.recordAuthorization(context, finalCmdId, authorized = false, reason = "RULE_BLOCKED")
                }
                config.appendQqRemoteLog("规则阻止执行 -> ${command.targetNumber}$simSuffix")
                return
            }
        }

        if (finalCmdId.isNotBlank()) {
            RemoteCommandRepository.recordAuthorization(context, finalCmdId, authorized = true, reason = "QQ_AUTHORIZED")
        }

        RemoteSmsCommandWorker.enqueue(
            context = context,
            target = command.targetNumber,
            content = command.content,
            subId = -1,
            uniqueId = fingerprint,
            sendMode = sendMode,
            requester = requester,
            source = SOURCE_QQ,
            commandId = finalCmdId,
        )
    }

    companion object {
        private const val TAG = "QqRemoteClient"
        private var activeInstance: QqRemoteClient? = null

        fun sendReply(requester: String, text: String) {
            val ws = activeInstance?.webSocket ?: return
            if (requester.isBlank() || text.isBlank()) return
            val payload = JSONObject()
            if (requester.startsWith("qq_group:")) {
                val parts = requester.split(":")
                val groupId = parts.getOrNull(1)?.toLongOrNull() ?: return
                payload.put("action", "send_group_msg")
                payload.put("params", JSONObject().put("group_id", groupId).put("message", text))
            } else if (requester.startsWith("qq_user:")) {
                val userId = requester.removePrefix("qq_user:").toLongOrNull() ?: return
                payload.put("action", "send_private_msg")
                payload.put("params", JSONObject().put("user_id", userId).put("message", text))
            }
            ws.send(payload.toString())
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
