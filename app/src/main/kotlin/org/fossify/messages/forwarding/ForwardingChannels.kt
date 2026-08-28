package org.fossify.messages.forwarding

object ForwardingChannels {
    const val ALL = "all"
    const val PUSHPLUS = "pushplus"
    const val WECHAT_TEST = "wechat_test"
    const val QQ = "qq"
    const val WECOM_APP = "wecom_app"
    const val WECOM = "wecom"
    const val WECOM_BOT = "wecom_bot"
    const val FEISHU_APP = "feishu_app"
    const val FEISHU_BOT = "feishu_bot"
    const val FEISHU = "feishu"
    const val DINGTALK = "dingtalk"
    const val BARK = "bark"
    const val WEBSOCKET = "websocket"
    const val TELEGRAM = "telegram"
    const val DISCORD = "discord"
    const val TENCENT_CLOUD = "tencent_cloud"
    const val EMAIL = "email"
    const val SMS_DIRECT = "sms_direct"
    const val CUSTOM_WEBHOOK = "custom_webhook"
    const val CHANNEL_GROUP = "channel_group"
    const val GOTIFY = "gotify"

    val networkChannels = setOf(
        PUSHPLUS, WECHAT_TEST, QQ, WECOM_APP, WECOM, WECOM_BOT,
        FEISHU_APP, FEISHU_BOT, FEISHU, DINGTALK, BARK, WEBSOCKET,
        TELEGRAM, DISCORD, TENCENT_CLOUD, EMAIL, CUSTOM_WEBHOOK, GOTIFY
    )

    val allRuleChannels = listOf(
        PUSHPLUS, WECHAT_TEST, QQ, WECOM_APP, WECOM_BOT,
        FEISHU_APP, FEISHU_BOT, DINGTALK, BARK, WEBSOCKET,
        TELEGRAM, DISCORD, TENCENT_CLOUD, EMAIL, SMS_DIRECT, CUSTOM_WEBHOOK, CHANNEL_GROUP, GOTIFY
    )

    val lowBatteryChannels = listOf(
        PUSHPLUS, BARK, GOTIFY, DINGTALK, FEISHU, WECOM, WECOM_BOT, EMAIL, SMS_DIRECT
    )

    fun displayName(channel: String): String = when (channel) {
        PUSHPLUS -> "PushPlus 微信推送"
        WECHAT_TEST -> "微信测试号"
        QQ -> "QQ 消息 (Qmsg/OneBot)"
        WECOM, WECOM_APP -> "企业微信应用号"
        WECOM_BOT -> "企业微信群机器人"
        FEISHU_APP -> "飞书自建应用"
        FEISHU, FEISHU_BOT -> "飞书群机器人"
        DINGTALK -> "钉钉群机器人"
        BARK -> "Bark (iOS)"
        WEBSOCKET -> "WebSocket 客户端"
        TELEGRAM -> "Telegram 机器人"
        DISCORD -> "Discord 群机器人"
        TENCENT_CLOUD -> "腾讯云自定义告警"
        EMAIL -> "邮件消息 (SMTP)"
        SMS_DIRECT -> "短信直发 (SIM)"
        CUSTOM_WEBHOOK -> "自定义 Webhook"
        CHANNEL_GROUP -> "群组聚合消息"
        GOTIFY -> "Gotify"
        else -> channel
    }
}
