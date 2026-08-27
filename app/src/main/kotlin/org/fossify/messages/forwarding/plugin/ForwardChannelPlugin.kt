package org.fossify.messages.forwarding.plugin

import android.content.Context
import org.fossify.messages.forwarding.plugin.model.ChannelResult
import org.fossify.messages.forwarding.plugin.model.ForwardPayload

/**
 * 转发通道插件通用标准契约接口
 */
interface ForwardChannelPlugin {

    /** 插件全局唯一标识 (如 "pushplus", "dingtalk_bot", "feishu", "sms_direct", "custom_webhook") */
    val pluginId: String

    /** 插件人类可读名称 */
    val displayName: String

    /** 校验通道配置合法性 */
    fun validateConfig(config: Map<String, String>): Boolean

    /** 执行消息转发 */
    suspend fun send(context: Context, payload: ForwardPayload): ChannelResult
}
