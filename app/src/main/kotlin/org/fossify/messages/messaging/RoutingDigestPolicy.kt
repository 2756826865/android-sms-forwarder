package org.fossify.messages.messaging

/**
 * Only settings which select a destination or render a forwarded message affect pending routing.
 */
internal object RoutingDigestPolicy {
    private val channels =
        listOf(
            "pushplus_",
            "wechat_test_",
            "qq_",
            "wecom_",
            "feishu_",
            "dingtalk_",
            "bark_",
            "websocket_",
            "telegram_",
            "discord_",
            "tencent_cloud_",
            "email_",
            "sms_direct_",
            "custom_webhook_",
            "channel_group_",
            "gotify_",
            "ntfy_",
            "serverchan_",
        )
    private val rendering =
        setOf(
            "template_mode",
            "custom_template",
            "enable_privacy_mask",
            "mask_verification_code",
            "forwarding_delay_seconds",
            "channel_instances",
        )

    fun includes(file: String, key: String): Boolean {
        if (
            key.contains("log", true) ||
                key.contains("status", true) ||
                key.startsWith("last", true)
        )
            return false
        return when (file) {
            "forwarding_rules" -> key in setOf("enabled", "scope", "rules")
            "pushplus_forwarding" ->
                key in
                    setOf(
                        "enabled",
                        "token",
                        "topic",
                        "title_prefix",
                        "include_sender",
                        "include_sim",
                        "include_time",
                    )
            "multi_channel_forwarding" ->
                key in rendering || (!key.contains("remote") && channels.any(key::startsWith))
            else -> false
        }
    }
}
