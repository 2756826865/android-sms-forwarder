package org.fossify.messages.autoreply

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AutoReplyConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var dailyLimit: Int
        get() = prefs.getInt(KEY_DAILY_LIMIT, 20).coerceIn(1, 100)
        set(value) = prefs.edit().putInt(KEY_DAILY_LIMIT, value.coerceIn(1, 100)).apply()

    var rules: List<AutoReplyRule>
        get() = decodeRules(prefs.getString(KEY_RULES, null).orEmpty())
        set(value) = prefs.edit().putString(KEY_RULES, encodeRules(value)).apply()

    var lastDecision: String
        get() = prefs.getString(KEY_LAST_DECISION, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_DECISION, value).apply()

    fun summary(): String = if (!enabled) {
        "未启用"
    } else {
        "已启用 · ${rules.count { it.enabled }} 条生效规则 · 每日上限 $dailyLimit 条"
    }

    private fun encodeRules(rules: List<AutoReplyRule>): String = JSONArray().apply {
        rules.forEach { rule ->
            put(
                JSONObject()
                    .put("id", rule.id)
                    .put("name", rule.name)
                    .put("enabled", rule.enabled)
                    .put("senderFilter", rule.senderFilter)
                    .put("includeKeywords", JSONArray(rule.includeKeywords))
                    .put("excludeKeywords", JSONArray(rule.excludeKeywords))
                    .put("includeRegex", rule.includeRegex)
                    .put("replyContent", rule.replyContent)
                    .put("simScope", rule.simScope)
                    .put("rateLimitMinutes", rule.rateLimitMinutes)
                    .put("delaySeconds", rule.delaySeconds)
                    .put("notifyReceipt", rule.notifyReceipt)
            )
        }
    }.toString()

    private fun decodeRules(value: String): List<AutoReplyRule> = runCatching {
        if (value.isBlank()) return@runCatching emptyList()
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val rateMin = if (item.has("rateLimitMinutes")) {
                    item.optInt("rateLimitMinutes", 1440)
                } else {
                    item.optInt("rateLimitHours", 24) * 60
                }
                add(
                    AutoReplyRule(
                        id = item.optString("id", java.util.UUID.randomUUID().toString()),
                        name = item.optString("name"),
                        enabled = item.optBoolean("enabled", true),
                        senderFilter = item.optString("senderFilter"),
                        includeKeywords = item.optJSONArray("includeKeywords").toStringList(),
                        excludeKeywords = item.optJSONArray("excludeKeywords").toStringList(),
                        includeRegex = item.optString("includeRegex"),
                        replyContent = item.optString("replyContent"),
                        simScope = item.optString("simScope", AutoReplyRule.SIM_SAME),
                        rateLimitMinutes = rateMin,
                        delaySeconds = item.optInt("delaySeconds", 3),
                        notifyReceipt = item.optBoolean("notifyReceipt", true),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "sms_auto_reply"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DAILY_LIMIT = "daily_limit"
        private const val KEY_RULES = "rules"
        private const val KEY_LAST_DECISION = "last_decision"
    }
}
