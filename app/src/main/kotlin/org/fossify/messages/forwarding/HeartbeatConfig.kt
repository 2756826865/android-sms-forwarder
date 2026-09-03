package org.fossify.messages.forwarding

import android.content.Context

class HeartbeatConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 间隔小时数：1, 3, 6, 12, 24 */
    var intervalHours: Int
        get() = prefs.getInt(KEY_INTERVAL_HOURS, 12)
        set(value) = prefs.edit().putInt(KEY_INTERVAL_HOURS, value).apply()

    var lastReportTime: Long
        get() = prefs.getLong(KEY_LAST_REPORT_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_REPORT_TIME, value).apply()

    var customTemplate: String
        get() = prefs.getString(KEY_CUSTOM_TEMPLATE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CUSTOM_TEMPLATE, value).apply()

    companion object {
        private const val PREFS_NAME = "heartbeat_config"
        private const val KEY_ENABLED = "heartbeat_enabled"
        private const val KEY_INTERVAL_HOURS = "heartbeat_interval_hours"
        private const val KEY_LAST_REPORT_TIME = "heartbeat_last_report_time"
        private const val KEY_CUSTOM_TEMPLATE = "heartbeat_custom_template"
    }
}
