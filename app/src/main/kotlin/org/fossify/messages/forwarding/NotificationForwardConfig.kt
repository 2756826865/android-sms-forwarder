package org.fossify.messages.forwarding

import android.content.Context
import android.provider.Telephony

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
        get() {
            val saved = prefs.getStringSet(KEY_TARGET_PACKAGES, null)
            return if (saved != null) {
                // 升级兼容：自动合并系统短信包名，确保老版本升级用户不会因历史配置缺失 OEM 短信兜底
                saved.toSet() + SYSTEM_SMS_PACKAGES
            } else {
                DEFAULT_PACKAGES
            }
        }
        set(value) = prefs.edit().putStringSet(KEY_TARGET_PACKAGES, value.toSet()).apply()

    var customTemplate: String
        get() = prefs.getString(KEY_CUSTOM_TEMPLATE, DEFAULT_TEMPLATE).orEmpty().ifBlank { DEFAULT_TEMPLATE }
        set(value) = prefs.edit().putString(KEY_CUSTOM_TEMPLATE, value.trim()).apply()

    /**
     * 仅在未设为系统默认短信应用时才转发「系统短信类应用」的通知。
     * 开启后：
     * - 当本应用已被 vivo/OPPO/魅族等系统夺回默认短信角色时，自动通过通知栏监听系统短信兜底转发；
     * - 当本应用正常持有默认短信角色时，自动跳过系统短信应用通知，防止与正常 SMS_DELIVER 广播重复转发两遍。
     */
    var skipSystemSmsWhenDefault: Boolean
        get() = prefs.getBoolean(KEY_SKIP_SYSTEM_SMS_WHEN_DEFAULT, true)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_SYSTEM_SMS_WHEN_DEFAULT, value).apply()

    companion object {
        private const val PREFS_NAME = "notification_forward_config"
        private const val KEY_ENABLED = "notification_forward_enabled"
        private const val KEY_IGNORE_ONGOING = "notification_forward_ignore_ongoing"
        private const val KEY_CHANNEL_INSTANCE_IDS = "notification_forward_channel_instance_ids"
        private const val KEY_TARGET_PACKAGES = "notification_forward_target_packages"
        private const val KEY_CUSTOM_TEMPLATE = "notification_forward_custom_template"
        private const val KEY_SKIP_SYSTEM_SMS_WHEN_DEFAULT = "notification_forward_skip_system_sms_when_default"

        const val DEFAULT_TEMPLATE = "【{{APP_NAME}} 通知】\n{{TITLE}}\n{{CONTENT}}"

        // 各主流品牌系统短信应用包名集合（用于防夺权通知兜底模式与智能防重）
        val SYSTEM_SMS_PACKAGES = setOf(
            "com.android.mms",                  // 原生 / MIUI / HyperOS / Flyme 系统短信
            "com.android.mms.service",          // 原生短信服务
            "com.vivo.message",                 // vivo / iQOO 系统信息
            "com.coloros.mms",                  // OPPO / 一加 / Realme 系统信息
            "com.coloros.mcs",                  // OPPO / 一加 / Realme 消息服务
            "com.heytap.mcs",                   // 欢太消息服务（ColorOS 常用）
            "com.oplus.mms",                    // Oplus 新版信息（ColorOS 13/14/15/17）
            "com.oneplus.mms",                  // 一加信息
            "com.huawei.message",               // 华为系统信息
            "com.hihonor.mms",                  // 荣耀系统信息
            "com.samsung.android.messaging",    // 三星系统信息
            "com.google.android.apps.messaging",// Google 信息
            "com.meizu.mms",                    // 魅族系统信息
            "com.xiaomi.xmsf"                   // 小米系统服务
        )

        fun isSystemSmsPackage(context: Context, packageName: String): Boolean {
            if (packageName.isBlank()) return false
            if (packageName in SYSTEM_SMS_PACKAGES) return true
            val currentDefault = runCatching {
                Telephony.Sms.getDefaultSmsPackage(context)
            }.getOrNull()
            return !currentDefault.isNullOrBlank() && packageName == currentDefault
        }

        // 默认预设的目标监听常用应用（微信、QQ、支付宝、钉钉、飞书、企业微信、云闪付、各品牌系统短信）
        val DEFAULT_PACKAGES = setOf(
            "com.tencent.mm",              // 微信
            "com.tencent.mobileqq",         // QQ
            "com.eg.android.AlipayGphone",  // 支付宝
            "com.alibaba.android.rimet",    // 钉钉
            "com.ss.android.lark",          // 飞书
            "com.tencent.wework",          // 企业微信
            "com.unionpay"                 // 云闪付
        ) + SYSTEM_SMS_PACKAGES
    }
}
