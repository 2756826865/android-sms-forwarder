package org.fossify.messages.forwarding.plugin

import org.fossify.messages.forwarding.plugin.impl.HttpChannelPlugin
import org.fossify.messages.forwarding.plugin.impl.SmsDirectChannelPlugin
import java.util.concurrent.ConcurrentHashMap

/**
 * 转发通道插件管理器 (统一注册与动态发现)
 */
object ChannelPluginManager {

    private val plugins = ConcurrentHashMap<String, ForwardChannelPlugin>()

    init {
        // 注册内置默认通道插件
        registerPlugin(SmsDirectChannelPlugin())
        registerPlugin(HttpChannelPlugin("pushplus", "PushPlus"))
        registerPlugin(HttpChannelPlugin("dingtalk_bot", "DingTalk Bot"))
        registerPlugin(HttpChannelPlugin("feishu", "Feishu Bot"))
        registerPlugin(HttpChannelPlugin("wechat_work", "WeChat Work Bot"))
        registerPlugin(HttpChannelPlugin("bark", "Bark iOS"))
        registerPlugin(HttpChannelPlugin("custom_webhook", "Custom Webhook"))
    }

    fun registerPlugin(plugin: ForwardChannelPlugin) {
        plugins[plugin.pluginId.lowercase()] = plugin
    }

    fun unregisterPlugin(pluginId: String) {
        plugins.remove(pluginId.lowercase())
    }

    fun getPlugin(pluginId: String): ForwardChannelPlugin? {
        return plugins[pluginId.lowercase()]
    }

    fun getAllPlugins(): List<ForwardChannelPlugin> {
        return plugins.values.toList()
    }
}
