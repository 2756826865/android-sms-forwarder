package org.fossify.messages.forwarding

object ForwardingChannels {
    const val ALL = "all"
    const val PUSHPLUS = "pushplus"
    const val DINGTALK = "dingtalk"
    const val FEISHU = "feishu"
    const val WECOM = "wecom"
    const val WECOM_BOT = "wecom_bot"
    const val EMAIL = "email"
    const val SMS_DIRECT = "sms_direct"
    const val BARK = "bark"
    const val GOTIFY = "gotify"
    const val WECHAT_TEST = "wechat_test"
    const val TELEGRAM = "telegram"
    const val CUSTOM_WEBHOOK = "custom_webhook"
    const val DISCORD = "discord"

    val networkChannels = setOf(
        PUSHPLUS, DINGTALK, FEISHU, WECOM, WECOM_BOT, EMAIL, BARK, GOTIFY,
        WECHAT_TEST, TELEGRAM, CUSTOM_WEBHOOK, DISCORD
    )
    val allRuleChannels = listOf(
        PUSHPLUS, DINGTALK, FEISHU, WECOM, WECOM_BOT, EMAIL, SMS_DIRECT, BARK, GOTIFY,
        WECHAT_TEST, TELEGRAM, CUSTOM_WEBHOOK, DISCORD
    )
    val lowBatteryChannels = listOf(
        PUSHPLUS, BARK, GOTIFY, WECHAT_TEST, TELEGRAM, CUSTOM_WEBHOOK, DISCORD,
        DINGTALK, FEISHU, WECOM, WECOM_BOT, EMAIL, SMS_DIRECT
    )

    fun displayName(channel: String): String = when (channel) {
        PUSHPLUS -> "PushPlus"
        DINGTALK -> "钉钉"
        FEISHU -> "飞书"
        WECOM -> "企业微信"
        WECOM_BOT -> "企业微信群机器人"
        EMAIL -> "邮箱"
        SMS_DIRECT -> "短信直发"
        BARK -> "Bark"
        GOTIFY -> "Gotify"
        WECHAT_TEST -> "微信测试号"
        TELEGRAM -> "Telegram"
        CUSTOM_WEBHOOK -> "自定义 Webhook"
        DISCORD -> "Discord"
        else -> channel
    }
}
