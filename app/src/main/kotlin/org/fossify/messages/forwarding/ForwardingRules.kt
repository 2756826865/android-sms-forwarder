package org.fossify.messages.forwarding

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ForwardingRulesConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** @see [SCOPE_FORWARDING_ONLY] [SCOPE_FORWARDING_AND_SMS_DIRECT] [SCOPE_ALL] */
    var scope: Int
        get() = prefs.getInt(KEY_SCOPE, SCOPE_FORWARDING_ONLY).coerceIn(SCOPE_FORWARDING_ONLY, SCOPE_ALL)
        set(value) = prefs.edit().putInt(KEY_SCOPE, value.coerceIn(SCOPE_FORWARDING_ONLY, SCOPE_ALL)).apply()

    var rules: List<ForwardingRule>
        get() = decodeRules(prefs.getString(KEY_RULES, null).orEmpty())
        set(value) = prefs.edit().putString(KEY_RULES, encodeRules(value)).apply()

    var lastDecision: String
        get() = prefs.getString(KEY_LAST_DECISION, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_DECISION, value).apply()

    fun summary(): String = if (!enabled) {
        "未启用 · ${scopeLabel(scope)}"
    } else {
        "已启用 · ${rules.size} 条规则 · ${scopeLabel(scope)}"
    }

    fun channelCandidatesForScope(enabledChannels: Set<String>): Set<String> = when (scope) {
        SCOPE_FORWARDING_AND_SMS_DIRECT, SCOPE_ALL -> enabledChannels
        else -> enabledChannels - ForwardingChannels.SMS_DIRECT
    }

    fun affectsRemoteCommands(): Boolean = enabled && scope == SCOPE_ALL

    private fun encodeRules(rules: List<ForwardingRule>): String = JSONArray().apply {
        rules.forEach { rule ->
            put(
                JSONObject()
                    .put("name", rule.name)
                    .put("enabled", rule.enabled)
                    .put("matchMode", rule.matchMode)
                    .put("sim", rule.simScope)
                    .put("includeKeywords", JSONArray(rule.includeKeywords))
                    .put("excludeKeywords", JSONArray(rule.excludeKeywords))
                    .put("includeRegex", rule.includeRegex)
                    .put("excludeRegex", rule.excludeRegex)
                    .put("channels", JSONArray(rule.channels))
            )
        }
    }.toString()

    private fun decodeRules(value: String): List<ForwardingRule> = runCatching {
        if (value.isBlank()) return@runCatching emptyList()
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    ForwardingRule(
                        name = item.optString("name"),
                        enabled = item.optBoolean("enabled", true),
                        matchMode = item.optString("matchMode", ForwardingRule.MATCH_ALL),
                        simScope = item.optString("sim", ForwardingRule.SIM_ALL),
                        includeKeywords = item.optJSONArray("includeKeywords").toStringList(),
                        excludeKeywords = item.optJSONArray("excludeKeywords").toStringList(),
                        includeRegex = item.optString("includeRegex"),
                        excludeRegex = item.optString("excludeRegex"),
                        channels = item.optJSONArray("channels").toStringList().ifEmpty { ForwardingChannels.allRuleChannels },
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
        private const val PREFS_NAME = "forwarding_rules"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SCOPE = "scope"
        private const val KEY_RULES = "rules"
        private const val KEY_LAST_DECISION = "last_decision"

        const val SCOPE_FORWARDING_ONLY = 0
        const val SCOPE_FORWARDING_AND_SMS_DIRECT = 1
        const val SCOPE_ALL = 2

        fun scopeLabel(scope: Int): String = when (scope) {
            SCOPE_FORWARDING_AND_SMS_DIRECT -> "规则 + 短信直发"
            SCOPE_ALL -> "全部功能受规则控制"
            else -> "仅规则转发"
        }
    }
}

data class ForwardingRule(
    val name: String,
    val enabled: Boolean = true,
    val matchMode: String = MATCH_ALL,
    val simScope: String = SIM_ALL,
    val includeKeywords: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
    val includeRegex: String = "",
    val excludeRegex: String = "",
    val channels: List<String> = ForwardingChannels.allRuleChannels,
) {
    companion object {
        const val SIM_ALL = "ALL"
        const val SIM_1 = "SIM1"
        const val SIM_2 = "SIM2"
        const val MATCH_ALL = "ALL"
        const val MATCH_ANY = "ANY"
    }
}

data class ForwardingRuleDecision(
    val allowedChannels: Set<String>,
    val blockedChannels: Set<String>,
    val matchedRules: List<ForwardingRule>,
    val reason: String,
) {
    fun isAllowed(channel: String) = allowedChannels.contains(channel)
}

class ForwardingRuleEngine(private val rules: List<ForwardingRule>) {
    fun evaluate(sender: String, body: String, subscriptionId: Int, channelCandidates: Set<String>, simSlotIndex: Int? = null): ForwardingRuleDecision {
        val activeRules = rules.filter { it.enabled }
        if (activeRules.isEmpty()) {
            return ForwardingRuleDecision(channelCandidates, emptySet(), emptyList(), "无启用规则，允许转发")
        }

        val sourceSlot = simSlotIndex
        val messageText = "$sender\n$body"
        val matched = activeRules.filter { it.matches(messageText, sourceSlot) }
        if (matched.isEmpty()) {
            return ForwardingRuleDecision(emptySet(), channelCandidates, emptyList(), "没有命中允许规则，阻止转发渠道")
        }

        val allowed = matched
            .flatMap { it.channels }
            .filter { it in channelCandidates }
            .toSet()
        return ForwardingRuleDecision(
            allowedChannels = allowed,
            blockedChannels = channelCandidates - allowed,
            matchedRules = matched,
            reason = if (allowed.isEmpty()) "规则命中但未选择当前渠道" else "命中 ${matched.size} 条规则",
        )
    }

    private fun ForwardingRule.matches(text: String, slot: Int?): Boolean {
        if (!simMatches(simScope, slot)) return false
        if (excludeKeywords.any { text.contains(it, ignoreCase = true) }) return false
        if (excludeRegex.isNotBlank() && runCatching { Regex(excludeRegex, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false)) return false

        val positiveConditions = buildList {
            if (includeKeywords.isNotEmpty()) {
                add(includeKeywords.any { text.contains(it, ignoreCase = true) })
            }
            if (includeRegex.isNotBlank()) {
                add(runCatching { Regex(includeRegex, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false))
            }
        }
        if (positiveConditions.isEmpty()) return true
        return if (matchMode == ForwardingRule.MATCH_ANY) {
            positiveConditions.any { it }
        } else {
            positiveConditions.all { it }
        }
    }

    private fun simMatches(scope: String, slot: Int?): Boolean = when (scope) {
        ForwardingRule.SIM_1 -> slot == 0
        ForwardingRule.SIM_2 -> slot == 1
        else -> true
    }
}
