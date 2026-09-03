package org.fossify.messages.forwarding

import android.content.Context
import org.json.JSONArray

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

    companion object {
        private const val PREFS_NAME = "call_forward_config"
        private const val KEY_ENABLED = "call_forward_enabled"
        private const val KEY_MISSED_CALL_ONLY = "call_forward_missed_only"
        private const val KEY_FORWARD_ANSWERED_CALL = "call_forward_answered"
        private const val KEY_CUSTOM_TEMPLATE = "call_forward_custom_template"
    }
}
