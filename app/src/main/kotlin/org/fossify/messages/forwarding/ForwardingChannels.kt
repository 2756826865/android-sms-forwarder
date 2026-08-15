package org.fossify.messages.forwarding

object ForwardingChannels {
    const val ALL = "test"
    const val PUSHPLUS = "pushplus"
    const val DINGTALK = "dingtalk"
    const val FEISHU = "feishu"
    const val WECOM = "wecom"
    const val WECOM_BOT = "wecom_bot"
    const val EMAIL = "email"
    const val SMS_DIRECT = "sms_direct"
    const val BARK = "bark"
    const val GOTIFY = "gotify"

    val networkChannels = setOf(PUSHPLUS, DINGTALK, FEISHU, WECOM, WECOM_BOT, EMAIL, BARK, GOTIFY)
    val allRuleChannels = listOf(PUSHPLUS, DINGTALK, FEISHU, WECOM, WECOM_BOT, EMAIL, SMS_DIRECT, BARK, GOTIFY)
    val lowBatteryChannels = listOf(PUSHPLUS, BARK, GOTIFY, DINGTALK, FEISHU, WECOM, WECOM_BOT, EMAIL, SMS_DIRECT)

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
        else -> channel
    }
}
