package org.fossify.messages.helpers

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Typed preference encoding shared by portable feature settings and local rollback journals. */
internal object BackupPreferences {
    fun encode(values: Map<String, *>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) ->
            val type = when (value) {
                is String -> "string"; is Boolean -> "boolean"; is Int -> "int"
                is Long -> "long"; is Float -> "float"; is Set<*> -> "set"
                else -> error("Unsupported preference type")
            }
            put(key, JSONObject().put("type", type).put("value", if (value is Set<*>) JSONArray(value.toList()) else value))
        }
    }
    fun decode(json: JSONObject): Map<String, Any> = json.keys().asSequence().associateWith { key ->
        val entry = json.getJSONObject(key)
        val value = entry.get("value")
        when (entry.getString("type")) {
            "string" -> { require(value is String); value }
            "boolean" -> { require(value is Boolean); value }
            "int" -> { require(value is Number && value.toDouble() == value.toInt().toDouble()); value.toInt() }
            "long" -> { require(value is Number && value.toDouble() == value.toLong().toDouble()); value.toLong() }
            "float" -> { require(value is Number && value.toFloat().isFinite()); value.toFloat() }
            "set" -> { val array = entry.getJSONArray("value"); (0 until array.length()).map { require(array.get(it) is String); array.getString(it) }.toSet() }
            else -> error("Unsupported preference type")
        }
    }
    fun write(prefs: SharedPreferences, values: Map<String, Any>, replace: Boolean = false) {
        val editor = prefs.edit()
        if (replace) editor.clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> { require(value.all { it is String }); editor.putStringSet(key, value.map { it as String }.toSet()) }
            else -> error("Unsupported preference type")
        } }
        check(editor.commit()) { "Configuration write failed" }
    }
    // Export only explicitly chosen non-credential settings; never copy arbitrary preferences across devices.
    val portableKeys: Map<String, Set<String>> = mapOf(
        "forwarding_fallback" to emptySet(), // instance IDs are dynamic; all string values allowed below
        "multi_channel_forwarding" to setOf("sim_one_label", "sim_two_label", "sim_one_number", "sim_two_number", "forwarding_delay_seconds", "keep_alive_service_enabled", "root_enhancement_enabled", "enable_privacy_mask", "mask_verification_code", "mark_as_read_after_forward", "sms_direct_only_on_no_network", "dingtalk_enabled", "feishu_enabled", "email_enabled", "sms_direct_enabled", "bark_enabled", "gotify_enabled", "pushplus_enabled", "wechat_test_enabled", "qq_enabled", "wecom_enabled", "wecom_bot_enabled", "feishu_app_enabled", "websocket_enabled", "telegram_enabled", "discord_enabled", "tencent_cloud_enabled", "custom_webhook_enabled", "channel_group_enabled", "bark_allow_http", "gotify_allow_http"),
        "autofill_config" to setOf("enabled", "auto_submit", "copy_to_clipboard", "enable_floating_pill", "excluded_packages"),
        "heartbeat_config" to setOf("heartbeat_enabled", "heartbeat_interval_hours", "heartbeat_custom_template", "heartbeat_channel_instance_ids"),
        "call_forward_config" to setOf("call_forward_enabled", "call_forward_missed_only", "call_forward_answered", "call_forward_custom_template", "call_forward_channel_instance_ids"),
        "notification_forward_config" to setOf("notification_forward_enabled", "notification_forward_ignore_ongoing", "notification_forward_channel_instance_ids", "notification_forward_target_packages", "notification_forward_custom_template"),
        "thread_layout" to setOf("composer_offset_dp")
    )
    fun export(context: Context): JSONObject = JSONObject().apply {
        portableKeys.forEach { (name, keys) ->
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                .filter { (key, value) -> if (name == "forwarding_fallback") value is String else key in keys }
            put(name, encode(values))
        }
    }
    fun parse(json: JSONObject): Map<String, Map<String, Any>> = json.keys().asSequence().associateWith { name ->
        require(name in portableKeys)
        decode(json.getJSONObject(name)).also { values ->
            require(values.keys.all { name == "forwarding_fallback" || it in portableKeys.getValue(name) })
            values.forEach { (key, value) ->
                when {
                    name == "forwarding_fallback" || key.startsWith("sim_") || key.endsWith("template") -> require(value is String)
                    key.endsWith("instance_ids") || key.endsWith("packages") -> require(value is Set<*>)
                    key in setOf("forwarding_delay_seconds", "composer_offset_dp", "heartbeat_interval_hours") -> require(value is Int)
                    else -> require(value is Boolean)
                }
                if (key == "forwarding_delay_seconds") require((value as Int) in 0..60)
                if (key == "composer_offset_dp") require((value as Int) in 0..160)
                if (key == "heartbeat_interval_hours") require(value in listOf(1, 3, 6, 12, 24))
            }
        }
    }
}
