package org.fossify.messages.remote.runtime

import android.content.Context
import android.util.Log
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.remote.DingTalkStreamClient
import org.fossify.messages.remote.EmailRemoteCommandPoller
import org.fossify.messages.remote.FeishuStreamClient
import org.fossify.messages.remote.RemoteControlPendingReceipt
import org.fossify.messages.remote.TelegramRemotePoller
import org.fossify.messages.remote.WeComStreamClient
import org.fossify.messages.remote.WebSocketRemoteClient
import org.fossify.messages.remote.repository.RemoteSourceConnectionState
import org.fossify.messages.remote.repository.RemoteSourceInstance
import org.fossify.messages.remote.repository.RemoteSourceRepository
import org.fossify.messages.remote.repository.RemoteSourceType
import org.fossify.messages.services.DingTalkRemoteControlService
import org.fossify.messages.services.EmailRemoteControlService
import org.fossify.messages.services.FeishuRemoteControlService
import org.fossify.messages.services.TelegramRemoteControlService
import org.fossify.messages.services.WebSocketRemoteControlService
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程指令来源唯一运行时管理器
 *
 * 核心架构原则：
 * 1. RemoteSourceRepository 是唯一事实源（不依赖旧版扁平布尔开关）；
 * 2. 统一管理多来源、多实例的底层网络长连接/轮询生命周期；
 * 3. 前台 Service 仅作为 Android 前台保活与通知载体，不重复创建网络客户端；
 * 4. 精准根据 sourceInstanceId 路由执行与原路回执，禁止跨实例回退；
 * 5. 动态响应实例的新增、修改、启用、禁用与删除。
 */
class RemoteSourceRuntimeManager private constructor(private val appContext: Context) {

    data class WeComPushResult(
        val isSuccess: Boolean,
        val code: String,
        val message: String,
        val weComErrorCode: Int? = null,
    )

    sealed class RuntimeHandle {
        abstract val instanceId: String
        abstract val configFingerprint: String
        val isStopped = java.util.concurrent.atomic.AtomicBoolean(false)
        abstract fun stop()

        data class Passive(
            override val instanceId: String,
            override val configFingerprint: String,
        ) : RuntimeHandle() {
            override fun stop() = Unit
        }

        data class Telegram(
            override val instanceId: String,
            override val configFingerprint: String,
            val poller: TelegramRemotePoller
        ) : RuntimeHandle() {
            override fun stop() = poller.stop()
        }

        data class WebSocket(
            override val instanceId: String,
            override val configFingerprint: String,
            val client: WebSocketRemoteClient
        ) : RuntimeHandle() {
            override fun stop() = client.stop()
        }

        data class DingTalk(
            override val instanceId: String,
            override val configFingerprint: String,
            val client: DingTalkStreamClient
        ) : RuntimeHandle() {
            override fun stop() = client.stop()
        }

        data class Feishu(
            override val instanceId: String,
            override val configFingerprint: String,
            val client: FeishuStreamClient
        ) : RuntimeHandle() {
            override fun stop() = client.stop()
        }

        data class WeCom(
            override val instanceId: String,
            override val configFingerprint: String,
            val client: WeComStreamClient
        ) : RuntimeHandle() {
            override fun stop() = client.stop()
        }

        data class Email(
            override val instanceId: String,
            override val configFingerprint: String,
            val poller: EmailRemoteCommandPoller
        ) : RuntimeHandle() {
            override fun stop() = poller.stop()
        }
    }

    private val runningHandles = ConcurrentHashMap<String, RuntimeHandle>()

    /**
     * 计算实例配置的特征指纹，用于检测配置是否被用户修改
     */
    private fun computeConfigFingerprint(instance: RemoteSourceInstance): String {
        return "${instance.type.name}#${instance.configJson}#${instance.customCommandPrefix}#" +
            "${instance.whitelistEnabled}#" +
            "${instance.authorizedUsers.sorted().joinToString(",")}#" +
            "${instance.authorizedGroups.sorted().joinToString(",")}#" +
            "${instance.requireMention}#${instance.defaultSimMode}#" +
            "${instance.quietHoursEnabled}#${instance.quietHoursStart}#${instance.quietHoursEnd}#" +
            "${instance.hourlyLimit}#${instance.dailyLimit}"
    }

    /**
     * 同步并启动/停止所有远程来源实例
     *
     * 1. 停止已被禁用或删除的实例；
     * 2. 对已在运行但配置发生变化的实例，执行热重启（先 stopSource 再 startSource）；
     * 3. 启动新启用的实例；
     * 4. 联动前台守护服务状态。
     */
    @Synchronized
    fun sync() {
        val repo = RemoteSourceRepository.getInstance(appContext)
        val enabledSources = repo.getEnabledSources()
        val runnableSources = enabledSources.filter { it.hasValidCredentials() }
        val runnableMap = runnableSources.associateBy { it.id }

        enabledSources.filterNot { it.hasValidCredentials() }.forEach { instance ->
            repo.updateConnectionState(
                instance.id,
                RemoteSourceConnectionState.CONFIG_REQUIRED,
                errorMessage = "来源配置不完整"
            )
        }

        // 1. 检查已运行的实例：如果已禁用/删除，或者配置发生变更，则先停止旧实例
        runningHandles.entries.forEach { (runningId, handle) ->
            val currentInstance = runnableMap[runningId]
            if (currentInstance == null) {
                // 已被禁用或删除
                stopSource(runningId)
            } else if (handle.configFingerprint != computeConfigFingerprint(currentInstance)) {
                // 用户修改了 Token / URL / 账号 / 白名单等配置，停止旧实例以便重新创建连接
                Log.i(TAG, "Config changed for instance $runningId, stopping for reload...")
                stopSource(runningId)
            }
        }

        // 2. 启动新启用或需要重建的实例
        enabledSources.forEach { instance ->
            if (!runningHandles.containsKey(instance.id) && instance.hasValidCredentials()) {
                startSource(instance)
            }
        }

        // 3. 联动前台守护服务状态 (仅依据 Repository 事实源)
        syncForegroundServices(runnableSources)
    }

    /**
     * 启动指定来源实例
     */
    @Synchronized
    fun startSource(instance: RemoteSourceInstance) {
        if (!instance.enabled || !instance.hasValidCredentials()) return
        stopSource(instance.id)

        val repo = RemoteSourceRepository.getInstance(appContext)
        repo.updateConnectionState(instance.id, RemoteSourceConnectionState.CONNECTING)

        val fingerprint = computeConfigFingerprint(instance)

        when (instance.type) {
            RemoteSourceType.SMS -> {
                runningHandles[instance.id] = RuntimeHandle.Passive(instance.id, fingerprint)
                repo.updateConnectionState(instance.id, RemoteSourceConnectionState.READY)
            }
            RemoteSourceType.TELEGRAM -> {
                val poller = TelegramRemotePoller(
                    context = appContext,
                    sourceInstanceId = instance.id,
                    onStatus = { status ->
                        MultiForwardConfig(appContext).appendTelegramRemoteLog("[${instance.name}] $status")
                    }
                )
                runningHandles[instance.id] = RuntimeHandle.Telegram(instance.id, fingerprint, poller)
                poller.start()
            }
            RemoteSourceType.WEBSOCKET -> {
                var handleRef: RuntimeHandle.WebSocket? = null
                val client = WebSocketRemoteClient(
                    context = appContext,
                    sourceInstanceId = instance.id,
                    onStatus = { status ->
                        val currentHandle = handleRef ?: return@WebSocketRemoteClient
                        if (!isHandleActive(currentHandle)) return@WebSocketRemoteClient
                        MultiForwardConfig(appContext).appendWebSocketRemoteLog("[${instance.name}] $status")
                    }
                )
                val handle = RuntimeHandle.WebSocket(instance.id, fingerprint, client)
                handleRef = handle
                runningHandles[instance.id] = handle
                client.start()
            }
            RemoteSourceType.DINGTALK -> {
                val clientId = instance.optString("clientId")
                val clientSecret = instance.optString("clientSecret")
                if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
                    var handleRef: RuntimeHandle.DingTalk? = null
                    val client = DingTalkStreamClient(
                        clientId = clientId,
                        clientSecret = clientSecret,
                        customPrefix = instance.customCommandPrefix,
                        onCommand = { cmd ->
                            val currentHandle = handleRef ?: return@DingTalkStreamClient
                            if (!isHandleActive(currentHandle)) return@DingTalkStreamClient
                            val envelope = org.fossify.messages.remote.RemoteCommandEnvelope(
                                sourceType = RemoteSourceType.DINGTALK,
                                sourceInstanceId = instance.id,
                                sourceMessageKey = cmd.messageId,
                                senderId = cmd.senderId.ifBlank { cmd.senderNick },
                                senderName = cmd.senderNick,
                                groupId = cmd.conversationId.takeIf { cmd.conversationType == "2" }.orEmpty(),
                                rawContent = cmd.rawContent,
                                isMentioned = cmd.isMentioned,
                                receivedAt = System.currentTimeMillis(),
                                extraMeta = mapOf("receiptTarget" to cmd.sessionWebhook)
                            )
                            org.fossify.messages.remote.RemoteCommandProcessor.process(appContext, envelope)
                        },
                        onStatus = { status ->
                            val currentHandle = handleRef ?: return@DingTalkStreamClient
                            if (!isHandleActive(currentHandle)) return@DingTalkStreamClient
                            MultiForwardConfig(appContext).appendDingTalkRemoteLog("[${instance.name}] $status")
                            if (status.contains("连接成功") || status.contains("已连接") || status.contains("就绪")) {
                                repo.updateConnectionState(instance.id, RemoteSourceConnectionState.READY)
                            } else if (status.contains("失败") || status.contains("异常") || status.contains("终止")) {
                                repo.updateConnectionState(instance.id, RemoteSourceConnectionState.ERROR, errorMessage = status)
                            }
                        }
                    )
                    val handle = RuntimeHandle.DingTalk(instance.id, fingerprint, client)
                    handleRef = handle
                    runningHandles[instance.id] = handle
                    client.start()
                }
            }
            RemoteSourceType.FEISHU -> {
                val appId = instance.optString("appId")
                val appSecret = instance.optString("appSecret")
                if (appId.isNotBlank() && appSecret.isNotBlank()) {
                    var handleRef: RuntimeHandle.Feishu? = null
                    val client = FeishuStreamClient(
                        appId = appId,
                        appSecret = appSecret,
                        customPrefix = instance.customCommandPrefix,
                        onCommand = { cmd ->
                            val currentHandle = handleRef ?: return@FeishuStreamClient
                            if (!isHandleActive(currentHandle)) return@FeishuStreamClient
                            val envelope = org.fossify.messages.remote.RemoteCommandEnvelope(
                                sourceType = RemoteSourceType.FEISHU,
                                sourceInstanceId = instance.id,
                                sourceMessageKey = cmd.messageId,
                                senderId = cmd.senderId,
                                groupId = cmd.chatId.takeIf { cmd.chatType == "group" }.orEmpty(),
                                rawContent = cmd.rawContent,
                                isMentioned = cmd.isMentioned,
                                receivedAt = System.currentTimeMillis(),
                                extraMeta = mapOf(
                                    "receiptTarget" to if (cmd.chatId.isNotBlank()) {
                                        "chat_id:${cmd.chatId}"
                                    } else {
                                        "open_id:${cmd.senderId}"
                                    }
                                )
                            )
                            org.fossify.messages.remote.RemoteCommandProcessor.process(appContext, envelope)
                        },
                        onStatus = { status ->
                            val currentHandle = handleRef ?: return@FeishuStreamClient
                            if (!isHandleActive(currentHandle)) return@FeishuStreamClient
                            MultiForwardConfig(appContext).appendFeishuRemoteLog("[${instance.name}] $status")
                            if (status.contains("已就绪") || status.contains("已连接")) {
                                repo.updateConnectionState(instance.id, RemoteSourceConnectionState.READY)
                            } else if (status.contains("失败") || status.contains("异常")) {
                                repo.updateConnectionState(instance.id, RemoteSourceConnectionState.ERROR, errorMessage = status)
                            }
                        }
                    )
                    val handle = RuntimeHandle.Feishu(instance.id, fingerprint, client)
                    handleRef = handle
                    runningHandles[instance.id] = handle
                    client.start()
                }
            }
            RemoteSourceType.WECOM -> {
                val botId = instance.optString("botId")
                val secret = instance.optString("secret")
                if (botId.isNotBlank() && secret.isNotBlank()) {
                    var handleRef: RuntimeHandle.WeCom? = null
                    val client = WeComStreamClient(
                        botId = botId,
                        secret = secret,
                        customPrefix = instance.customCommandPrefix,
                        onCommand = { cmd ->
                            val currentHandle = handleRef ?: return@WeComStreamClient
                            if (!isHandleActive(currentHandle)) return@WeComStreamClient
                            val envelope = org.fossify.messages.remote.RemoteCommandEnvelope(
                                sourceType = RemoteSourceType.WECOM,
                                sourceInstanceId = instance.id,
                                sourceMessageKey = cmd.msgId,
                                senderId = cmd.senderId,
                                groupId = cmd.chatId.takeIf { cmd.chatType == "group" }.orEmpty(),
                                rawContent = cmd.rawContent,
                                isMentioned = cmd.isMentioned,
                                receivedAt = System.currentTimeMillis(),
                                extraMeta = mapOf(
                                    "receiptTarget" to cmd.reqId
                                )
                            )
                            org.fossify.messages.remote.RemoteCommandProcessor.process(appContext, envelope)
                        },
                        onStatus = { status ->
                            val currentHandle = handleRef ?: return@WeComStreamClient
                            if (!isHandleActive(currentHandle)) return@WeComStreamClient
                            if (status.startsWith("收到消息 -> ")) {
                                val captured = if (status.contains("群聊 Chat ID: ")) {
                                    status.substringAfter("群聊 Chat ID: ").substringBefore(" ").trim()
                                } else if (status.contains("单聊 User ID: ")) {
                                    status.substringAfter("单聊 User ID: ").substringBefore(" ").trim()
                                } else ""
                                if (captured.isNotBlank()) {
                                    MultiForwardConfig(appContext).lastCapturedWeComChatId = captured
                                }
                            }
                            MultiForwardConfig(appContext).appendWeComRemoteLog("[${instance.name}] $status")
                            if (status.contains("已就绪") || status.contains("已连接")) {
                                repo.updateConnectionState(instance.id, RemoteSourceConnectionState.READY)
                            } else if (status.contains("失败") || status.contains("异常") || status.contains("断开")) {
                                repo.updateConnectionState(instance.id, RemoteSourceConnectionState.ERROR, errorMessage = status)
                            }
                        }
                    )
                    val handle = RuntimeHandle.WeCom(instance.id, fingerprint, client)
                    handleRef = handle
                    runningHandles[instance.id] = handle
                    client.start()
                }
            }
            RemoteSourceType.EMAIL -> {
                var handleRef: RuntimeHandle.Email? = null
                val poller = EmailRemoteCommandPoller(appContext, sourceInstanceId = instance.id)
                val handle = RuntimeHandle.Email(instance.id, fingerprint, poller)
                handleRef = handle
                runningHandles[instance.id] = handle
                poller.start(intervalMs = 60_000L, onStatus = { status ->
                    val currentHandle = handleRef ?: return@start
                    if (!isHandleActive(currentHandle)) return@start
                    MultiForwardConfig(appContext).appendEmailRemoteLog("[${instance.name}] $status")
                })
            }
        }
    }

    private fun isHandleActive(handle: RuntimeHandle): Boolean {
        return !handle.isStopped.get() && runningHandles[handle.instanceId] === handle
    }

    /**
     * 停止指定来源实例
     */
    @Synchronized
    fun stopSource(instanceId: String) {
        val handle = runningHandles.remove(instanceId)
        handle?.isStopped?.set(true)
        try {
            handle?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping runtime handle for $instanceId", e)
        }
        val repo = RemoteSourceRepository.getInstance(appContext)
        val current = repo.getSourceById(instanceId)
        if (current != null && !current.enabled) {
            repo.updateConnectionState(instanceId, RemoteSourceConnectionState.DISABLED)
        }
    }

    /**
     * 原路回执派发 (严格依据 sourceInstanceId 路由，禁止 cross-instance 回退)
     */
    fun sendDirectReceipt(pending: RemoteControlPendingReceipt, status: String, body: String): Boolean {
        val sourceInstanceId = pending.sourceInstanceId
        if (sourceInstanceId.isBlank()) return false

        val repo = RemoteSourceRepository.getInstance(appContext)
        val instance = repo.getSourceById(sourceInstanceId) ?: return false

        return when (instance.type) {
            RemoteSourceType.TELEGRAM -> {
                TelegramRemotePoller.sendReply(appContext, pending.requester, "【短信远程指令回执】\n$body", sourceInstanceId)
            }
            RemoteSourceType.WEBSOCKET -> {
                WebSocketRemoteClient.sendReceiptOverSocket(pending.commandId, status, body, sourceInstanceId)
            }
            RemoteSourceType.DINGTALK -> {
                val handle = runningHandles[sourceInstanceId] as? RuntimeHandle.DingTalk ?: return false
                handle.client.sendReply(pending.requester, "【短信远程指令回执】\n$body")
            }
            RemoteSourceType.FEISHU -> {
                val handle = runningHandles[sourceInstanceId] as? RuntimeHandle.Feishu ?: return false
                handle.client.sendReply(pending.requester, "【短信远程指令回执】\n$body")
            }
            RemoteSourceType.WECOM -> {
                val handle = runningHandles[sourceInstanceId] as? RuntimeHandle.WeCom ?: return false
                handle.client.sendReply(pending.requester, "【短信远程指令回执】\n$body")
            }
            RemoteSourceType.SMS,
            RemoteSourceType.EMAIL -> false
        }
    }

    private fun syncForegroundServices(enabledSources: List<RemoteSourceInstance>) {
        val hasTg = enabledSources.any { it.type == RemoteSourceType.TELEGRAM }
        val hasWs = enabledSources.any { it.type == RemoteSourceType.WEBSOCKET }
        val hasDing = enabledSources.any { it.type == RemoteSourceType.DINGTALK }
        val hasFeishu = enabledSources.any { it.type == RemoteSourceType.FEISHU }
        val hasWeCom = enabledSources.any { it.type == RemoteSourceType.WECOM }
        val hasEmail = enabledSources.any { it.type == RemoteSourceType.EMAIL }

        if (hasTg) TelegramRemoteControlService.ensureStarted(appContext) else TelegramRemoteControlService.stop(appContext)
        if (hasWs) WebSocketRemoteControlService.ensureStarted(appContext) else WebSocketRemoteControlService.stop(appContext)
        if (hasDing) DingTalkRemoteControlService.ensureStarted(appContext) else DingTalkRemoteControlService.stop(appContext)
        if (hasFeishu) FeishuRemoteControlService.ensureStarted(appContext) else FeishuRemoteControlService.stop(appContext)
        if (hasWeCom) org.fossify.messages.services.WeComRemoteControlService.ensureStarted(appContext) else org.fossify.messages.services.WeComRemoteControlService.stop(appContext)
        if (hasEmail) EmailRemoteControlService.ensureStarted(appContext) else EmailRemoteControlService.stop(appContext)
    }

    fun sendWeComPush(sourceInstanceId: String, chatId: String, content: String): WeComPushResult {
        if (sourceInstanceId.isBlank()) {
            return WeComPushResult(false, "source_missing", "推送通道尚未关联企业微信远程来源")
        }
        if (chatId.isBlank()) {
            return WeComPushResult(false, "chat_id_missing", "会话 ID 不能为空")
        }
        val source = RemoteSourceRepository.getInstance(appContext).getSourceById(sourceInstanceId)
            ?: return WeComPushResult(false, "source_not_found", "关联的企业微信远程来源已不存在")
        if (source.type != RemoteSourceType.WECOM) {
            return WeComPushResult(false, "source_type_invalid", "关联来源不是企业微信长连接")
        }
        if (!source.enabled) {
            return WeComPushResult(false, "source_disabled", "关联的企业微信远程来源已停用")
        }
        val handle = runningHandles[sourceInstanceId] as? RuntimeHandle.WeCom
            ?: return WeComPushResult(
                false,
                if (source.connectionState == RemoteSourceConnectionState.CONNECTING) "connecting" else "disconnected",
                if (source.connectionState == RemoteSourceConnectionState.CONNECTING) "企业微信长连接正在连接，请稍后重试"
                else source.lastErrorMessage.ifBlank { "企业微信长连接尚未运行" },
            )
        if (!isHandleActive(handle) || !handle.client.isReady()) {
            return WeComPushResult(false, "connecting", "企业微信长连接尚未完成连接与鉴权")
        }
        val result = handle.client.push(chatId.trim(), content)
        return WeComPushResult(result.isSuccess, result.code, result.message, result.weComErrorCode)
    }

    companion object {
        private const val TAG = "RemoteSourceRuntime"

        @Volatile
        private var INSTANCE: RemoteSourceRuntimeManager? = null

        fun getInstance(context: Context): RemoteSourceRuntimeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RemoteSourceRuntimeManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
