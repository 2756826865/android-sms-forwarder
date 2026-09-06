package org.fossify.messages.forwarding

import android.content.Context
class CallForwardConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var missedCallOnly: Boolean
        get() = prefs.getBoolean(KEY_MISSED_CALL_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_MISSED_CALL_ONLY, value).apply()

    var forwardAnsweredCall: Boolean
        get() = prefs.getBoolean(KEY_FORWARD_ANSWERED_CALL, false)
        set(value) = prefs.edit().putBoolean(KEY_FORWARD_ANSWERED_CALL, value).apply()

    var customTemplate: String
        get() = prefs.getString(KEY_CUSTOM_TEMPLATE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CUSTOM_TEMPLATE, value).apply()

    /** 未配置选择时保持旧版行为：发送到全部已启用通道。 */
    val hasChannelSelection: Boolean
        get() = prefs.contains(KEY_CHANNEL_INSTANCE_IDS)

    var channelInstanceIds: Set<String>
        get() = prefs.getStringSet(KEY_CHANNEL_INSTANCE_IDS, emptySet())?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_CHANNEL_INSTANCE_IDS, value.toSet()).apply()

    companion object {
        private const val PREFS_NAME = "call_forward_config"
        private const val KEY_ENABLED = "call_forward_enabled"
        private const val KEY_MISSED_CALL_ONLY = "call_forward_missed_only"
        private const val KEY_FORWARD_ANSWERED_CALL = "call_forward_answered"
        private const val KEY_CUSTOM_TEMPLATE = "call_forward_custom_template"
        private const val KEY_CHANNEL_INSTANCE_IDS = "call_forward_channel_instance_ids"
    }
}
