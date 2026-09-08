package org.fossify.messages.forwarding

import android.content.Context

class NotificationForwardConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var ignoreOngoing: Boolean
        get() = prefs.getBoolean(KEY_IGNORE_ONGOING, true)
        set(value) = prefs.edit().putBoolean(KEY_IGNORE_ONGOING, value).apply()

    val hasChannelSelection: Boolean
        get() = prefs.contains(KEY_CHANNEL_INSTANCE_IDS)

    var channelInstanceIds: Set<String>
        get() = prefs.getStringSet(KEY_CHANNEL_INSTANCE_IDS, emptySet())?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_CHANNEL_INSTANCE_IDS, value.toSet()).apply()

    var targetPackageNames: Set<String>
        get() = prefs.getStringSet(KEY_TARGET_PACKAGES, DEFAULT_PACKAGES)?.toSet() ?: DEFAULT_PACKAGES
        set(value) = prefs.edit().putStringSet(KEY_TARGET_PACKAGES, value.toSet()).apply()

    companion object {
        private const val PREFS_NAME = "notification_forward_config"
        private const val KEY_ENABLED = "notification_forward_enabled"
        private const val KEY_IGNORE_ONGOING = "notification_forward_ignore_ongoing"
        private const val KEY_CHANNEL_INSTANCE_IDS = "notification_forward_channel_instance_ids"
        private const val KEY_TARGET_PACKAGES = "notification_forward_target_packages"

        // 默认预设的目标监听常用应用（微信、QQ、支付宝、钉钉、飞书、企业微信、云闪付、系统短信）
        val DEFAULT_PACKAGES = setOf(
            "com.tencent.mm",              // 微信
            "com.tencent.mobileqq",         // QQ
            "com.eg.android.AlipayGphone",  // 支付宝
            "com.alibaba.android.rimet",    // 钉钉
            "com.ss.android.lark",          // 飞书
            "com.tencent.wework",          // 企业微信
            "com.unionpay",                // 云闪付
            "com.android.mms",             // 备用系统短信
            "com.google.android.apps.messaging" // 谷歌信息
        )
    }
}
