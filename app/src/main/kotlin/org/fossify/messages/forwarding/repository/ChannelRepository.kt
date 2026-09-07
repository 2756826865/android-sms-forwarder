package org.fossify.messages.forwarding.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fossify.messages.forwarding.ForwardingChannelInstance
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingRule
import org.fossify.messages.forwarding.MultiForwardConfig
import org.json.JSONObject
import java.util.UUID

data class LegacyChannelInfo(
    val channelType: String,
    val defaultName: String,
    val isEnabled: Boolean,
    val configJson: String
)

class ChannelRepository internal constructor(
    context: Context? = null,
    private val multiConfig: MultiForwardConfig = MultiForwardConfig(context!!.applicationContext)
) {
    private val appContext = context?.applicationContext

    private val _instancesFlow = MutableStateFlow<List<ForwardingChannelInstance>>(emptyList())
    val instancesFlow: StateFlow<List<ForwardingChannelInstance>> = _instancesFlow.asStateFlow()

    private val lock = Any()

    init {
        refresh()
        // 升级后立即将既有明文实例配置迁移为 Keystore 密文；保存逻辑会保护无法解密的旧密文。
        if (_instancesFlow.value.isNotEmpty()) {
            multiConfig.saveChannelInstances(_instancesFlow.value)
        }
        // 覆盖升级后自动把旧版真实配置转换为通道实例；导入按凭据幂等，不会重复创建。
        importLegacyChannels()
    }

    fun refresh() {
        synchronized(lock) {
            _instancesFlow.value = multiConfig.channelInstances()
        }
    }

    fun getInstances(): List<ForwardingChannelInstance> = synchronized(lock) {
        multiConfig.channelInstances()
    }

    fun getInstanceById(id: String): ForwardingChannelInstance? = synchronized(lock) {
        multiConfig.channelInstances().firstOrNull { it.id == id }
    }

    fun getEnabledInstances(): List<ForwardingChannelInstance> = synchronized(lock) {
        multiConfig.channelInstances().filter { it.enabled && it.hasDispatchConfiguration() }
    }

    fun saveInstance(instance: ForwardingChannelInstance) = synchronized(lock) {
        val current = multiConfig.channelInstances().toMutableList()
        val index = current.indexOfFirst { it.id == instance.id }
        if (index >= 0) {
            current[index] = instance
        } else {
            current.add(instance)
        }
        multiConfig.saveChannelInstances(current)
        _instancesFlow.value = current
    }

    fun deleteInstance(id: String): Boolean = synchronized(lock) {
        val current = multiConfig.channelInstances().toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            multiConfig.saveChannelInstances(current)
            _instancesFlow.value = current
        }
        removed
    }

    fun toggleInstanceEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val current = multiConfig.channelInstances().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = current[index].copy(enabled = enabled)
            multiConfig.saveChannelInstances(current)
            _instancesFlow.value = current
        }
    }

    fun getReferencingRules(instanceId: String): List<ForwardingRule> {
        val ctx = appContext ?: return emptyList()
        val ruleRepo = RuleRepository.getInstance(ctx)
        return ruleRepo.getRules().filter { rule ->
            rule.actions.any { it.targetInstanceId == instanceId } ||
                rule.targetInstanceIds.contains(instanceId)
        }
    }

    // ========================================================
    // 兼容迁移：检测并幂等导入旧版配置
    // ========================================================

    fun hasLegacyConfigToMigrate(): Boolean {
        val detected = detectLegacyConfiguredChannels()
        if (detected.isEmpty()) return false
        val existing = getInstances()
        // 若检测到的真实配置中有任何一个尚未导入为实例，则显示导入引导
        return detected.any { leg ->
            existing.none { it.channelType == leg.channelType }
        }
    }

    fun detectLegacyConfiguredChannels(): List<LegacyChannelInfo> {
        val list = mutableListOf<LegacyChannelInfo>()

        // 1. PushPlus
        if (multiConfig.pushPlusToken().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.PUSHPLUS,
                    defaultName = "PushPlus (导入)",
                    isEnabled = multiConfig.pushPlusEnabled,
                    configJson = JSONObject()
                        .put("token", multiConfig.pushPlusToken())
                        .put("topic", multiConfig.pushPlusTopic())
                        .toString()
                )
            )
        }

        // 2. 微信测试号
        if (multiConfig.wechatTestAppId().isNotBlank() && multiConfig.wechatTestAppSecret().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.WECHAT_TEST,
                    defaultName = "微信测试号 (导入)",
                    isEnabled = multiConfig.wechatTestEnabled,
                    configJson = JSONObject()
                        .put("appId", multiConfig.wechatTestAppId())
                        .put("appSecret", multiConfig.wechatTestAppSecret())
                        .put("openId", multiConfig.wechatTestOpenId())
                        .put("templateId", multiConfig.wechatTestTemplateId())
                        .toString()
                )
            )
        }

        // 3. 企微群机器人
        if (multiConfig.weComBotWebhook().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.WECOM_BOT,
                    defaultName = "企业微信群机器人 (导入)",
                    isEnabled = multiConfig.weComBotEnabled,
                    configJson = JSONObject().put("webhook", multiConfig.weComBotWebhook()).toString()
                )
            )
        }

        // 4. 企微自建应用
        if (multiConfig.weComCorpId().isNotBlank() && multiConfig.weComAgentId().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.WECOM_APP,
                    defaultName = "企业微信应用号 (导入)",
                    isEnabled = multiConfig.weComEnabled,
                    configJson = JSONObject()
                        .put("corpId", multiConfig.weComCorpId())
                        .put("agentId", multiConfig.weComAgentId())
                        .put("secret", multiConfig.weComSecret())
                        .put("toUser", multiConfig.weComToUser())
                        .toString()
                )
            )
        }

        // 5. 钉钉群机器人
        if (multiConfig.dingTalkWebhook().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.DINGTALK,
                    defaultName = "钉钉群机器人 (导入)",
                    isEnabled = multiConfig.dingTalkEnabled,
                    configJson = JSONObject()
                        .put("webhook", multiConfig.dingTalkWebhook())
                        .put("secret", multiConfig.dingTalkSecret())
                        .toString()
                )
            )
        }

        // 6. 飞书群机器人
        if (multiConfig.feishuWebhook().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.FEISHU_BOT,
                    defaultName = "飞书群机器人 (导入)",
                    isEnabled = multiConfig.feishuEnabled,
                    configJson = JSONObject()
                        .put("webhook", multiConfig.feishuWebhook())
                        .put("secret", multiConfig.feishuSecret())
                        .toString()
                )
            )
        }

        // 7. 飞书自建应用
        if (multiConfig.feishuAppId().isNotBlank() && multiConfig.feishuAppSecret().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.FEISHU_APP,
                    defaultName = "飞书自建应用 (导入)",
                    isEnabled = multiConfig.feishuAppEnabled,
                    configJson = JSONObject()
                        .put("appId", multiConfig.feishuAppId())
                        .put("appSecret", multiConfig.feishuAppSecret())
                        .put("receiveId", multiConfig.feishuReceiveId())
                        .toString()
                )
            )
        }

        // 8. Telegram
        if (multiConfig.telegramBotToken().isNotBlank() && multiConfig.telegramChatId().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.TELEGRAM,
                    defaultName = "Telegram 机器人 (导入)",
                    isEnabled = multiConfig.telegramEnabled,
                    configJson = JSONObject()
                        .put("botToken", multiConfig.telegramBotToken())
                        .put("chatId", multiConfig.telegramChatId())
                        .toString()
                )
            )
        }

        // 9. Bark
        if (multiConfig.barkDeviceKey().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.BARK,
                    defaultName = "Bark (导入)",
                    isEnabled = multiConfig.barkEnabled,
                    configJson = JSONObject()
                        .put("serverUrl", multiConfig.barkServerUrl())
                        .put("deviceKey", multiConfig.barkDeviceKey())
                        .toString()
                )
            )
        }

        // 10. QQ (Qmsg / OneBot)
        if (multiConfig.qqWebhook().isNotBlank()) {
            val qType = multiConfig.qqType()
            val json = JSONObject().put("type", qType)
            if (qType == "qmsg") {
                json.put("qmsgKey", multiConfig.qqWebhook())
            } else {
                json.put("onebotUrl", multiConfig.qqWebhook())
            }
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.QQ,
                    defaultName = "QQ 消息 (导入)",
                    isEnabled = multiConfig.qqEnabled,
                    configJson = json.toString()
                )
            )
        }

        // 11. 邮件
        if (multiConfig.emailHost().isNotBlank() && multiConfig.emailUser().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.EMAIL,
                    defaultName = "邮件推送 (导入)",
                    isEnabled = multiConfig.emailEnabled,
                    configJson = JSONObject()
                        .put("host", multiConfig.emailHost())
                        .put("port", multiConfig.emailPort)
                        .put("user", multiConfig.emailUser())
                        .put("password", multiConfig.emailPassword())
                        .put("recipients", multiConfig.emailRecipients())
                        .toString()
                )
            )
        }

        // 12. 自定义 Webhook
        if (multiConfig.customWebhookUrl().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.CUSTOM_WEBHOOK,
                    defaultName = "自定义 Webhook (导入)",
                    isEnabled = multiConfig.customWebhookEnabled,
                    configJson = JSONObject()
                        .put("url", multiConfig.customWebhookUrl())
                        .put("headers", multiConfig.customWebhookHeaders())
                        .put("method", multiConfig.customWebhookMethod())
                        .put("contentType", multiConfig.customWebhookContentType())
                        .put("bodyTemplate", multiConfig.customWebhookBodyTemplate())
                        .toString()
                )
            )
        }

        // 13. WebSocket
        if (multiConfig.websocketUrl().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.WEBSOCKET,
                    defaultName = "WebSocket (导入)",
                    isEnabled = multiConfig.websocketEnabled,
                    configJson = JSONObject()
                        .put("serverUrl", multiConfig.websocketUrl())
                        .put("token", multiConfig.websocketToken())
                        .toString()
                )
            )
        }

        // 14. Gotify
        if (multiConfig.gotifyServerUrl().isNotBlank() && multiConfig.gotifyToken().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.GOTIFY,
                    defaultName = "Gotify (导入)",
                    isEnabled = multiConfig.gotifyEnabled,
                    configJson = JSONObject()
                        .put("serverUrl", multiConfig.gotifyServerUrl())
                        .put("token", multiConfig.gotifyToken())
                        .toString()
                )
            )
        }

        // 15. 短信直发
        if (multiConfig.smsDirectPhone().isNotBlank()) {
            list.add(
                LegacyChannelInfo(
                    channelType = ForwardingChannels.SMS_DIRECT,
                    defaultName = "短信直发 (导入)",
                    isEnabled = multiConfig.smsDirectEnabled,
                    configJson = JSONObject().put("phone", multiConfig.smsDirectPhone()).toString()
                )
            )
        }

        return list
    }

    /**
     * 幂等导入旧版真实配置为实例，严禁生成未配置的内置通道，重复执行不会产生重复实例
     */
    fun importLegacyChannels(): Int = synchronized(lock) {
        val detected = detectLegacyConfiguredChannels()
        if (detected.isEmpty()) return 0

        val current = multiConfig.channelInstances().toMutableList()
        var importedCount = 0

        for (legacy in detected) {
            // 幂等防重：检查是否已有该类型的实例且配置主要凭据相同
            val alreadyExists = current.any { existing ->
                existing.channelType == legacy.channelType &&
                    isSameConfig(legacy.channelType, existing.configJson, legacy.configJson)
            }
            if (!alreadyExists) {
                current.add(
                    ForwardingChannelInstance(
                        id = UUID.randomUUID().toString(),
                        channelType = legacy.channelType,
                        name = legacy.defaultName,
                        enabled = legacy.isEnabled,
                        configJson = legacy.configJson
                    )
                )
                importedCount++
            }
        }

        if (importedCount > 0) {
            multiConfig.saveChannelInstances(current)
            _instancesFlow.value = current
        }

        importedCount
    }

    private fun isSameConfig(channelType: String, json1: String, json2: String): Boolean = runCatching {
        val o1 = JSONObject(json1)
        val o2 = JSONObject(json2)
        val identityKeys = when (channelType) {
            ForwardingChannels.PUSHPLUS -> listOf("token")
            ForwardingChannels.WECHAT_TEST -> listOf("appId", "openId")
            ForwardingChannels.QQ -> if (o1.optString("qmsgKey").isNotBlank() || o2.optString("qmsgKey").isNotBlank()) {
                listOf("qmsgKey")
            } else {
                listOf("onebotUrl")
            }
            ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> listOf("corpId", "agentId", "toUser")
            ForwardingChannels.WECOM_BOT,
            ForwardingChannels.FEISHU,
            ForwardingChannels.FEISHU_BOT,
            ForwardingChannels.DINGTALK,
            ForwardingChannels.DISCORD,
            ForwardingChannels.TENCENT_CLOUD -> listOf("webhook")
            ForwardingChannels.FEISHU_APP -> listOf("appId", "receiveId")
            ForwardingChannels.BARK -> listOf("serverUrl", "deviceKey")
            ForwardingChannels.WEBSOCKET -> listOf("serverUrl", "token")
            ForwardingChannels.TELEGRAM -> listOf("botToken", "chatId")
            ForwardingChannels.EMAIL -> listOf("host", "user")
            ForwardingChannels.SMS_DIRECT -> listOf("phone")
            ForwardingChannels.CUSTOM_WEBHOOK -> listOf("url")
            ForwardingChannels.GOTIFY -> listOf("serverUrl", "token")
            else -> emptyList()
        }
        identityKeys.isNotEmpty() &&
            identityKeys.any { o1.optString(it).isNotBlank() } &&
            identityKeys.all { o1.optString(it) == o2.optString(it) }
    }.getOrDefault(false)

    companion object {
        @Volatile
        private var instance: ChannelRepository? = null

        fun getInstance(context: Context): ChannelRepository =
            instance ?: synchronized(this) {
                instance ?: ChannelRepository(context).also { instance = it }
            }

        fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
