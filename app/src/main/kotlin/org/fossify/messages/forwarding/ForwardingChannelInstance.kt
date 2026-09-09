package org.fossify.messages.forwarding

import org.json.JSONObject
import java.util.UUID

/**
 * 转发渠道多实例实体
 * 支持用户针对同一种渠道类型（如企业微信群机器人、钉钉群机器人、Telegram、自定义 Webhook 等）
 * 创建多个独立的目标实例并赋予自定义名称，实现多群/多通道精准靶向分流。
 */
data class ForwardingChannelInstance(
    val id: String = UUID.randomUUID().toString(),
    val channelType: String,
    val name: String,
    val enabled: Boolean = true,
    val configJson: String = "{}",
) {
    fun optString(key: String, default: String = ""): String = runCatching {
        JSONObject(configJson).optString(key, default)
    }.getOrDefault(default)

    fun optBoolean(key: String, default: Boolean = false): Boolean = runCatching {
        JSONObject(configJson).optBoolean(key, default)
    }.getOrDefault(default)

    fun optInt(key: String, default: Int = 0): Int = runCatching {
        JSONObject(configJson).optInt(key, default)
    }.getOrDefault(default)

    /** Whether this instance contains the minimum fields required for forwarding dispatch. */
    fun hasDispatchConfiguration(): Boolean = when (channelType) {
        ForwardingChannels.PUSHPLUS -> optString("token").isNotBlank()
        ForwardingChannels.WECHAT_TEST -> listOf("appId", "appSecret", "templateId", "openId")
            .all { optString(it).isNotBlank() }
        ForwardingChannels.QQ -> optString("qmsgKey").isNotBlank() || optString("onebotUrl").isNotBlank()
        ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP ->
            listOf("corpId", "agentId", "secret", "toUser").all { optString(it).isNotBlank() }
        ForwardingChannels.WECOM_BOT -> optString("webhook").isNotBlank()
        ForwardingChannels.WECOM_STREAM ->
            optString("sourceInstanceId").isNotBlank() && optString("chatId").isNotBlank()
        ForwardingChannels.FEISHU_APP -> listOf("appId", "appSecret", "receiveId")
            .all { optString(it).isNotBlank() }
        ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT,
        ForwardingChannels.DINGTALK, ForwardingChannels.DISCORD,
        ForwardingChannels.TENCENT_CLOUD -> optString("webhook").isNotBlank()
        ForwardingChannels.BARK -> optString("serverUrl").isNotBlank() && optString("deviceKey").isNotBlank()
        ForwardingChannels.WEBSOCKET -> optString("serverUrl").isNotBlank()
        ForwardingChannels.TELEGRAM -> optString("botToken").isNotBlank() && optString("chatId").isNotBlank()
        ForwardingChannels.EMAIL -> listOf("host", "user", "password", "recipients")
            .all { optString(it).isNotBlank() }
        ForwardingChannels.SMS_DIRECT -> optString("phone").isNotBlank()
        ForwardingChannels.CUSTOM_WEBHOOK -> optString("url").isNotBlank()
        ForwardingChannels.GOTIFY -> optString("serverUrl").isNotBlank() && optString("token").isNotBlank()
        ForwardingChannels.NTFY -> optString("topic").isNotBlank()
        else -> false
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("channelType", channelType)
        .put("name", name)
        .put("enabled", enabled)
        .put("configJson", configJson)

    companion object {
        fun fromJson(json: JSONObject): ForwardingChannelInstance = ForwardingChannelInstance(
            id = json.optString("id", UUID.randomUUID().toString()),
            channelType = json.optString("channelType"),
            name = json.optString("name"),
            enabled = json.optBoolean("enabled", true),
            configJson = json.optString("configJson", "{}"),
        )
    }
}
