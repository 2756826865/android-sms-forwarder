package org.fossify.messages.forwarding

import org.fossify.messages.permissions.PermissionCapability

enum class ChannelCategory(val title: String, val emoji: String) {
    ALL("全部", "🌐"),
    WECHAT("微信生态", "🟢"),
    WORK("办公协同", "🏢"),
    INSTANT("极客通讯", "⚡"),
    CLOUD("云与自定义", "☁️")
}

data class ChannelDefinition(
    val id: String,
    val name: String,
    val description: String,
    val iconEmoji: String,
    val category: ChannelCategory,
    val isSmsChannel: Boolean = false,
    val isGroupChannel: Boolean = false,
    val supportsMultipleInstances: Boolean = true,
    val requiredPermissions: List<PermissionCapability> = emptyList()
)

object ChannelRegistry {
    val allDefinitions: List<ChannelDefinition> = listOf(
        ChannelDefinition(
            id = ForwardingChannels.PUSHPLUS,
            name = "PushPlus 微信推送",
            description = "免自建服务器，微信公众号极速实时触达",
            iconEmoji = "🟢",
            category = ChannelCategory.WECHAT
        ),
        ChannelDefinition(
            id = ForwardingChannels.WECHAT_TEST,
            name = "微信公众平台测试号",
            description = "专属模板消息推送，支持自定义颜色与标题",
            iconEmoji = "💬",
            category = ChannelCategory.WECHAT
        ),
        ChannelDefinition(
            id = ForwardingChannels.WECOM_BOT,
            name = "企业微信群机器人",
            description = "企微内部群 Webhook，支持图文与 Markdown",
            iconEmoji = "🤖",
            category = ChannelCategory.WECHAT
        ),
        ChannelDefinition(
            id = ForwardingChannels.WECOM_APP,
            name = "企业微信自建应用",
            description = "企业号应用消息，支持全员或精准指定接收人",
            iconEmoji = "🏢",
            category = ChannelCategory.WECHAT
        ),
        ChannelDefinition(
            id = ForwardingChannels.DINGTALK,
            name = "钉钉群机器人",
            description = "钉钉工作群 Webhook，支持加签安全验证",
            iconEmoji = "📌",
            category = ChannelCategory.WORK
        ),
        ChannelDefinition(
            id = ForwardingChannels.FEISHU_BOT,
            name = "飞书群机器人",
            description = "飞书自定义机器人 Webhook，支持签名加密",
            iconEmoji = "🚀",
            category = ChannelCategory.WORK
        ),
        ChannelDefinition(
            id = ForwardingChannels.FEISHU_APP,
            name = "飞书自建应用",
            description = "开放平台企业自建应用，私聊推送更私密",
            iconEmoji = "📨",
            category = ChannelCategory.WORK
        ),
        ChannelDefinition(
            id = ForwardingChannels.EMAIL,
            name = "邮件推送 (SMTP)",
            description = "标准 SMTP 协议，支持 QQ/163/Gmail/企业邮箱",
            iconEmoji = "📧",
            category = ChannelCategory.WORK
        ),
        ChannelDefinition(
            id = ForwardingChannels.TELEGRAM,
            name = "Telegram 机器人",
            description = "Telegram Bot API，支持国内反代与 Markdown",
            iconEmoji = "✈️",
            category = ChannelCategory.INSTANT
        ),
        ChannelDefinition(
            id = ForwardingChannels.QQ,
            name = "QQ 消息 (Qmsg/OneBot)",
            description = "支持 Qmsg 酱或自建 OneBot/NapCat 协议端",
            iconEmoji = "🐧",
            category = ChannelCategory.INSTANT
        ),
        ChannelDefinition(
            id = ForwardingChannels.BARK,
            name = "Bark (iOS)",
            description = "苹果 iOS 平台轻量推送神器，极低功耗",
            iconEmoji = "🔔",
            category = ChannelCategory.INSTANT
        ),
        ChannelDefinition(
            id = ForwardingChannels.DISCORD,
            name = "Discord 群机器人",
            description = "Discord 频道 Webhook，支持富文本 Embed",
            iconEmoji = "🎮",
            category = ChannelCategory.INSTANT
        ),
        ChannelDefinition(
            id = ForwardingChannels.CUSTOM_WEBHOOK,
            name = "自定义 Webhook",
            description = "通用 HTTP POST/GET 接口，对接自建后端",
            iconEmoji = "🌐",
            category = ChannelCategory.CLOUD
        ),
        ChannelDefinition(
            id = ForwardingChannels.WEBSOCKET,
            name = "WebSocket 客户端",
            description = "全双工长连接主动推送，微秒级延迟",
            iconEmoji = "🔌",
            category = ChannelCategory.CLOUD
        ),
        ChannelDefinition(
            id = ForwardingChannels.GOTIFY,
            name = "Gotify 自建推送",
            description = "开源私有化推送服务器，完全本地自主可控",
            iconEmoji = "🛡️",
            category = ChannelCategory.CLOUD
        ),
        ChannelDefinition(
            id = ForwardingChannels.NTFY,
            name = "ntfy 推送",
            description = "支持 ntfy.sh 与自建服务，可按 Topic 精准推送",
            iconEmoji = "📣",
            category = ChannelCategory.CLOUD
        ),
        ChannelDefinition(
            id = ForwardingChannels.TENCENT_CLOUD,
            name = "腾讯云自定义告警",
            description = "云监控自定义事件上报接口",
            iconEmoji = "☁️",
            category = ChannelCategory.CLOUD
        ),
        ChannelDefinition(
            id = ForwardingChannels.SMS_DIRECT,
            name = "短信直发 (SIM)",
            description = "使用备用机指定 SIM 卡向目标手机重发短信",
            iconEmoji = "📱",
            category = ChannelCategory.CLOUD,
            isSmsChannel = true,
            requiredPermissions = listOf(PermissionCapability.SEND_SMS)
        ),
        ChannelDefinition(
            id = ForwardingChannels.CHANNEL_GROUP,
            name = "群组聚合消息",
            description = "一键聚合触发多个已启用的转发渠道",
            iconEmoji = "👥",
            category = ChannelCategory.CLOUD,
            isGroupChannel = true
        )
    )

    private val definitionMap by lazy { allDefinitions.associateBy { it.id } }

    fun find(channelId: String): ChannelDefinition? = definitionMap[channelId]

    fun getDisplayName(channelId: String): String = find(channelId)?.name ?: ForwardingChannels.displayName(channelId)

    fun getIconEmoji(channelId: String): String = find(channelId)?.iconEmoji ?: "🔌"
}
