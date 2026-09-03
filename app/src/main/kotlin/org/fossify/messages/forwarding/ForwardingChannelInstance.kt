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
