package org.fossify.messages.forwarding

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

// ========================================================
// 1. 统一动作与匹配条件模型
// ========================================================

enum class RuleActionType {
    SEND_CHANNEL
}

enum class RuleTemplateMode {
    GLOBAL,
    CUSTOM
}

data class RegexReplacement(
    val pattern: String,
    val replacement: String,
    val ignoreCase: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pattern", pattern)
        .put("replacement", replacement)
        .put("ignoreCase", ignoreCase)

    companion object {
        fun fromJson(json: JSONObject): RegexReplacement = RegexReplacement(
            pattern = json.optString("pattern"),
            replacement = json.optString("replacement"),
            ignoreCase = json.optBoolean("ignoreCase", false)
        )
    }
}

data class ForwardingRuleAction(
    val id: String = UUID.randomUUID().toString(),
    val type: RuleActionType = RuleActionType.SEND_CHANNEL,
    val channelType: String,
    val targetInstanceId: String,
    val enabled: Boolean = true,
    val templateMode: RuleTemplateMode = RuleTemplateMode.GLOBAL,
    val customTemplate: String? = null,
    val regexReplacements: List<RegexReplacement> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("channelType", channelType)
        .put("targetInstanceId", targetInstanceId)
        .put("enabled", enabled)
        .put("templateMode", templateMode.name)
        .put("customTemplate", customTemplate.orEmpty())
        .put("regexReplacements", JSONArray().apply {
            regexReplacements.forEach { put(it.toJson()) }
        })

    companion object {
        fun fromJson(json: JSONObject): ForwardingRuleAction = ForwardingRuleAction(
            id = json.optString("id", UUID.randomUUID().toString()),
            type = runCatching { RuleActionType.valueOf(json.optString("type")) }.getOrDefault(RuleActionType.SEND_CHANNEL),
            channelType = json.optString("channelType"),
            targetInstanceId = json.optString("targetInstanceId"),
            enabled = json.optBoolean("enabled", true),
            templateMode = runCatching { RuleTemplateMode.valueOf(json.optString("templateMode")) }.getOrDefault(RuleTemplateMode.GLOBAL),
            customTemplate = json.optString("customTemplate").takeIf(String::isNotBlank),
            regexReplacements = buildList {
                val arr = json.optJSONArray("regexReplacements") ?: return@buildList
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(RegexReplacement.fromJson(it)) }
                }
            }
        )
    }
}

enum class RuleTargetField(val displayName: String) {
    SENDER("发件人号码"),
    BODY("短信正文")
}

enum class RuleOperator(val displayName: String) {
    CONTAINS("包含"),
    EQUALS("完全匹配"),
    REGEX("正则匹配")
}

enum class RuleConditionRelation(val displayName: String) {
    ALL("全部满足 (AND)"),
    ANY("任一满足 (OR)")
}

data class ForwardingRuleCondition(
    val id: String = UUID.randomUUID().toString(),
    val field: RuleTargetField,
    val operator: RuleOperator,
    val value: String,
    val ignoreCase: Boolean = true,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("field", field.name)
        .put("operator", operator.name)
        .put("value", value)
        .put("ignoreCase", ignoreCase)
        .put("enabled", enabled)

    companion object {
        fun fromJson(json: JSONObject): ForwardingRuleCondition = ForwardingRuleCondition(
            id = json.optString("id", UUID.randomUUID().toString()),
            field = runCatching { RuleTargetField.valueOf(json.optString("field")) }.getOrDefault(RuleTargetField.BODY),
            operator = runCatching { RuleOperator.valueOf(json.optString("operator")) }.getOrDefault(RuleOperator.CONTAINS),
            value = json.optString("value"),
            ignoreCase = json.optBoolean("ignoreCase", true),
            enabled = json.optBoolean("enabled", true)
        )
    }
}

data class ForwardingTarget(
    val ruleId: String,
    val actionId: String,
    val channelType: String,
    val instanceId: String,
    val renderedContent: String
)

// ========================================================
// 2. 统一规则实体定义 (ForwardingRule)
// ========================================================

data class ForwardingRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = false,
    val priority: Int = 0,
    val simScope: String = SIM_ALL,
    val conditionRelation: RuleConditionRelation = RuleConditionRelation.ALL,
    val conditions: List<ForwardingRuleCondition> = emptyList(),
    val actions: List<ForwardingRuleAction> = emptyList(),
    val customTemplate: String = "",
    val regexReplacements: List<RegexReplacement> = emptyList(),

    // 免打扰设置 (Do Not Disturb)
    val doNotDisturbEnabled: Boolean = false,
    val dndStart: String = "22:00",
    val dndEnd: String = "06:00",
    val dndDays: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),

    // 兼容历史字段 (保留以平滑兼容老数据与经典版)
    val matchMode: String = MATCH_ALL,
    val includeKeywords: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
    val includeRegex: String = "",
    val excludeRegex: String = "",
    val channels: List<String> = emptyList(),
    val targetInstanceIds: List<String> = emptyList(),
    val timeWindowEnabled: Boolean = false,
    val timeStart: String = "00:00",
    val timeEnd: String = "23:59",
    val activeDays: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7)
) {
    companion object {
        const val SIM_ALL = "ALL"
        const val SIM_1 = "SIM1"
        const val SIM_2 = "SIM2"
        const val MATCH_ALL = "ALL"
        const val MATCH_ANY = "ANY"
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

// ========================================================
// 3. 规则决策结果
// ========================================================

data class ForwardingRuleDecision(
    val targets: List<ForwardingTarget>,
    val allowedChannels: Set<String>,
    val blockedChannels: Set<String>,
    val matchedRules: List<ForwardingRule>,
    val allowedInstanceIds: Set<String> = emptySet(),
    val reason: String,
    val isDndBlocked: Boolean = false
) {
    fun isAllowed(channel: String) = allowedChannels.contains(channel)
    fun isInstanceAllowed(instanceId: String) = allowedInstanceIds.isEmpty() || allowedInstanceIds.contains(instanceId)
}

// ========================================================
// 4. 统一规则匹配引擎 (ForwardingRuleEngine)
// ========================================================

class ForwardingRuleEngine(
    private val rules: List<ForwardingRule>,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun evaluate(
        sender: String,
        body: String,
        subscriptionId: Int,
        channelCandidates: Set<String>,
        simSlotIndex: Int? = null,
        resolveContent: (ForwardingRule, ForwardingRuleAction, String) -> String = { _, _, text -> text }
    ): ForwardingRuleDecision {
        // priority 是持久化模型的一部分，必须参与真实执行。相同优先级仍保持管理页顺序，
        // 这样旧数据和用户通过“上移/下移”建立的顺序不会被打乱。
        val activeRules = rules.withIndex()
            .filter { it.value.enabled }
            .sortedWith(
                compareByDescending<IndexedValue<ForwardingRule>> { it.value.priority }
                    .thenBy { it.index }
            )
            .map { it.value }
        if (activeRules.isEmpty()) {
            return ForwardingRuleDecision(
                targets = emptyList(),
                allowedChannels = emptySet(),
                blockedChannels = channelCandidates,
                matchedRules = emptyList(),
                allowedInstanceIds = emptySet(),
                reason = "无启用规则，不发送"
            )
        }

        val matchedRules = mutableListOf<ForwardingRule>()
        val generatedTargets = mutableListOf<ForwardingTarget>()
        var dndBlockedCount = 0

        for (rule in activeRules) {
            // 1. 卡槽匹配
            if (!simMatches(rule.simScope, simSlotIndex)) {
                continue
            }

            // 2. 免打扰判定
            val dndActive = if (rule.doNotDisturbEnabled) {
                isTimeRangeActive(rule.dndStart, rule.dndEnd, rule.dndDays, clock())
            } else if (rule.timeWindowEnabled) {
                // 兼容老版本的反向时间窗口
                !isTimeRangeActive(rule.timeStart, rule.timeEnd, rule.activeDays, clock())
            } else {
                false
            }
            if (dndActive) {
                dndBlockedCount++
                continue
            }

            // 3. 条件匹配
            if (!matchesConditions(rule, sender, body)) {
                continue
            }

            matchedRules.add(rule)

            // 4. 执行按顺序的正则替换
            val textAfterRuleReplacements = applyRegexReplacements(body, rule.regexReplacements)

            // 5. 生成该规则的靶向动作目标
            if (rule.actions.isNotEmpty()) {
                val enabledActions = rule.actions.filter { it.enabled }
                for (action in enabledActions) {
                    val finalActionBody = applyRegexReplacements(textAfterRuleReplacements, action.regexReplacements)
                    val rendered = resolveContent(rule, action, finalActionBody)
                    generatedTargets.add(
                        ForwardingTarget(
                            ruleId = rule.id,
                            actionId = action.id,
                            channelType = action.channelType,
                            instanceId = action.targetInstanceId,
                            renderedContent = rendered
                        )
                    )
                }
            } else {
                // 兼容老版本 channels / targetInstanceIds
                val instanceTargets = rule.targetInstanceIds.filter(String::isNotBlank)
                if (instanceTargets.isNotEmpty()) {
                    instanceTargets.forEach { instId ->
                        generatedTargets.add(
                            ForwardingTarget(
                                ruleId = rule.id,
                                actionId = "legacy_action_${rule.id}_$instId",
                                channelType = "",
                                instanceId = instId,
                                renderedContent = textAfterRuleReplacements
                            )
                        )
                    }
                } else if (rule.channels.isNotEmpty()) {
                    rule.channels.filter { it in channelCandidates }.forEach { ch ->
                        generatedTargets.add(
                            ForwardingTarget(
                                ruleId = rule.id,
                                actionId = "legacy_action_${rule.id}_$ch",
                                channelType = ch,
                                instanceId = "",
                                renderedContent = textAfterRuleReplacements
                            )
                        )
                    }
                }
            }
        }

        if (matchedRules.isEmpty()) {
            val reason = if (dndBlockedCount > 0) "命中规则但处于免打扰时段，已跳过发送" else "没有命中任何启用的规则"
            return ForwardingRuleDecision(
                targets = emptyList(),
                allowedChannels = emptySet(),
                blockedChannels = channelCandidates,
                matchedRules = emptyList(),
                allowedInstanceIds = emptySet(),
                reason = reason,
                isDndBlocked = dndBlockedCount > 0
            )
        }

        // 去重目标：同一 instanceId 默认不重复投递
        val deduplicatedTargets = buildList {
            val seenInstances = mutableSetOf<String>()
            val seenChannels = mutableSetOf<String>()
            for (target in generatedTargets) {
                if (target.instanceId.isNotBlank()) {
                    if (seenInstances.add(target.instanceId)) {
                        add(target)
                    }
                } else if (target.channelType.isNotBlank()) {
                    if (seenChannels.add(target.channelType)) {
                        add(target)
                    }
                }
            }
        }

        val allowedChannels = deduplicatedTargets.map { it.channelType }.filter(String::isNotBlank).toSet()
        val allowedInstances = deduplicatedTargets.map { it.instanceId }.filter(String::isNotBlank).toSet()

        return ForwardingRuleDecision(
            targets = deduplicatedTargets,
            allowedChannels = allowedChannels,
            blockedChannels = channelCandidates - allowedChannels,
            matchedRules = matchedRules,
            allowedInstanceIds = allowedInstances,
            reason = "命中 ${matchedRules.size} 条规则，生成 ${deduplicatedTargets.size} 个投递目标"
        )
    }

    private fun matchesConditions(rule: ForwardingRule, sender: String, body: String): Boolean {
        // 如果有结构化新条件，以结构化条件为准
        if (rule.conditions.isNotEmpty()) {
            val activeConds = rule.conditions.filter { it.enabled }
            if (activeConds.isEmpty()) return false

            val results = activeConds.map { cond ->
                val targetText = when (cond.field) {
                    RuleTargetField.SENDER -> sender
                    RuleTargetField.BODY -> body
                }
                evaluateSingleCondition(targetText, cond.operator, cond.value, cond.ignoreCase)
            }

            return when (rule.conditionRelation) {
                RuleConditionRelation.ALL -> results.all { it }
                RuleConditionRelation.ANY -> results.any { it }
            }
        }

        // 回退逻辑：老版本旧字段检查
        val messageText = "$sender\n$body"
        if (rule.excludeKeywords.any { messageText.contains(it, ignoreCase = true) }) return false
        if (rule.excludeRegex.isNotBlank() && runCatching {
                Regex(rule.excludeRegex, RegexOption.IGNORE_CASE).containsMatchIn(messageText)
            }.getOrDefault(false)) {
            return false
        }

        val positive = buildList {
            if (rule.includeKeywords.isNotEmpty()) {
                add(rule.includeKeywords.any { messageText.contains(it, ignoreCase = true) })
            }
            if (rule.includeRegex.isNotBlank()) {
                add(runCatching {
                    Regex(rule.includeRegex, RegexOption.IGNORE_CASE).containsMatchIn(messageText)
                }.getOrDefault(false))
            }
        }
        if (positive.isEmpty()) return false
        return if (rule.matchMode == ForwardingRule.MATCH_ANY) positive.any { it } else positive.all { it }
    }

    private fun evaluateSingleCondition(
        text: String,
        operator: RuleOperator,
        value: String,
        ignoreCase: Boolean
    ): Boolean = runCatching {
        when (operator) {
            RuleOperator.CONTAINS -> text.contains(value, ignoreCase = ignoreCase)
            RuleOperator.EQUALS -> text.equals(value, ignoreCase = ignoreCase)
            RuleOperator.REGEX -> {
                val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                Regex(value, options).containsMatchIn(text)
            }
        }
    }.getOrDefault(false)

    fun applyRegexReplacements(content: String, replacements: List<RegexReplacement>): String {
        var result = content
        for (rep in replacements) {
            if (rep.pattern.isBlank()) continue
            result = runCatching {
                val options = if (rep.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                Regex(rep.pattern, options).replace(result, rep.replacement)
            }.getOrDefault(result)
        }
        return result
    }

    fun isTimeRangeActive(
        timeStart: String,
        timeEnd: String,
        activeDays: List<Int>,
        nowMillis: Long = clock()
    ): Boolean = runCatching {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val standardDay = when (dayOfWeek) {
            Calendar.SUNDAY -> 7
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 1
        }
        if (activeDays.isNotEmpty() && !activeDays.contains(standardDay)) {
            return false
        }

        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMinutes = parseMinutes(timeStart, 0)
        val endMinutes = parseMinutes(timeEnd, 23 * 60 + 59)

        if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            // 跨午夜场景，如 22:00 ~ 06:00
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }.getOrDefault(false)

    private fun parseMinutes(timeStr: String, defaultVal: Int): Int = runCatching {
        val parts = timeStr.trim().split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return defaultVal
        val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
        (hour.coerceIn(0, 23) * 60) + min.coerceIn(0, 59)
    }.getOrDefault(defaultVal)

    private fun simMatches(scope: String, slot: Int?): Boolean = when (scope) {
        ForwardingRule.SIM_1 -> slot == 0
        ForwardingRule.SIM_2 -> slot == 1
        else -> true
    }
}

// ========================================================
// 5. 持久化存储与 Schema v2 编解码 (ForwardingRulesConfig)
// ========================================================

class ForwardingRulesConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

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

    fun encodeRules(rules: List<ForwardingRule>): String = JSONArray().apply {
        rules.forEach { rule ->
            put(
                JSONObject()
                    .put("schemaVersion", ForwardingRule.CURRENT_SCHEMA_VERSION)
                    .put("id", rule.id)
                    .put("name", rule.name)
                    .put("enabled", rule.enabled)
                    .put("priority", rule.priority)
                    .put("sim", rule.simScope)
                    .put("conditionRelation", rule.conditionRelation.name)
                    .put("conditions", JSONArray().apply {
                        rule.conditions.forEach { put(it.toJson()) }
                    })
                    .put("actions", JSONArray().apply {
                        rule.actions.forEach { put(it.toJson()) }
                    })
                    .put("customTemplate", rule.customTemplate)
                    .put("regexReplacements", JSONArray().apply {
                        rule.regexReplacements.forEach { put(it.toJson()) }
                    })
                    .put("doNotDisturbEnabled", rule.doNotDisturbEnabled)
                    .put("dndStart", rule.dndStart)
                    .put("dndEnd", rule.dndEnd)
                    .put("dndDays", JSONArray(rule.dndDays))
                    // 兼容字段保留
                    .put("matchMode", rule.matchMode)
                    .put("includeKeywords", JSONArray(rule.includeKeywords))
                    .put("excludeKeywords", JSONArray(rule.excludeKeywords))
                    .put("includeRegex", rule.includeRegex)
                    .put("excludeRegex", rule.excludeRegex)
                    .put("channels", JSONArray(rule.channels))
                    .put("targetInstanceIds", JSONArray(rule.targetInstanceIds))
                    .put("timeWindowEnabled", rule.timeWindowEnabled)
                    .put("timeStart", rule.timeStart)
                    .put("timeEnd", rule.timeEnd)
                    .put("activeDays", JSONArray(rule.activeDays))
            )
        }
    }.toString()

    fun decodeRules(value: String): List<ForwardingRule> = runCatching {
        if (value.isBlank()) return@runCatching emptyList()
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val schemaVersion = item.optInt("schemaVersion", 1)

                val id = item.optString("id", UUID.randomUUID().toString())
                val name = item.optString("name")
                val enabled = item.optBoolean("enabled", false)
                val priority = item.optInt("priority", 0)
                val simScope = item.optString("sim", ForwardingRule.SIM_ALL)
                val conditionRelation = runCatching {
                    RuleConditionRelation.valueOf(item.optString("conditionRelation"))
                }.getOrDefault(RuleConditionRelation.ALL)

                // 解析 conditions
                val conditions = buildList {
                    val condArray = item.optJSONArray("conditions")
                    if (condArray != null) {
                        for (i in 0 until condArray.length()) {
                            condArray.optJSONObject(i)?.let { add(ForwardingRuleCondition.fromJson(it)) }
                        }
                    }
                }

                // 解析 actions
                val actions = buildList {
                    val actionArray = item.optJSONArray("actions")
                    if (actionArray != null) {
                        for (i in 0 until actionArray.length()) {
                            actionArray.optJSONObject(i)?.let { add(ForwardingRuleAction.fromJson(it)) }
                        }
                    }
                }

                val customTemplate = item.optString("customTemplate")
                val regexReplacements = buildList {
                    val repArray = item.optJSONArray("regexReplacements") ?: return@buildList
                    for (i in 0 until repArray.length()) {
                        repArray.optJSONObject(i)?.let { add(RegexReplacement.fromJson(it)) }
                    }
                }

                val dndEnabled = item.optBoolean("doNotDisturbEnabled", item.optBoolean("timeWindowEnabled", false))
                val dndStart = item.optString("dndStart", item.optString("timeStart", "22:00"))
                val dndEnd = item.optString("dndEnd", item.optString("timeEnd", "06:00"))
                val dndDays = item.optJSONArray("dndDays")?.toIntList()
                    ?: item.optJSONArray("activeDays")?.toIntList()
                    ?: listOf(1, 2, 3, 4, 5, 6, 7)

                // 历史字段严格按规则区分：
                // 情况 A：无 channels 键 -> 旧版本数据
                // 情况 B：明确有 channels 键 -> 严格保留其内容，即使为空数组也是用户未选择
                val hasChannelsField = item.has("channels")
                val channels = if (!hasChannelsField && schemaVersion < 2) {
                    // 旧版本数据无 channels 键，按旧版语义
                    ForwardingChannels.allRuleChannels
                } else {
                    item.optJSONArray("channels").toStringList()
                }

                val targetInstanceIds = item.optJSONArray("targetInstanceIds").toStringList()
                val includeKeywords = item.optJSONArray("includeKeywords").toStringList()
                val excludeKeywords = item.optJSONArray("excludeKeywords").toStringList()
                val includeRegex = item.optString("includeRegex")
                val excludeRegex = item.optString("excludeRegex")

                // 如果 actions 为空且来自旧版本，则从 channels/targetInstanceIds 幂等迁移
                val resolvedActions = if (actions.isEmpty() && (channels.isNotEmpty() || targetInstanceIds.isNotEmpty())) {
                    buildList {
                        targetInstanceIds.forEach { instId ->
                            add(
                                ForwardingRuleAction(
                                    channelType = "",
                                    targetInstanceId = instId,
                                    enabled = true
                                )
                            )
                        }
                        channels.forEach { ch ->
                            add(
                                ForwardingRuleAction(
                                    channelType = ch,
                                    targetInstanceId = "",
                                    enabled = true
                                )
                            )
                        }
                    }
                } else {
                    actions
                }

                // 如果 conditions 为空且来自旧版本，则从 includeKeywords/includeRegex 幂等迁移
                val resolvedConditions = if (conditions.isEmpty() && (includeKeywords.isNotEmpty() || includeRegex.isNotBlank())) {
                    buildList {
                        includeKeywords.forEach { kw ->
                            add(
                                ForwardingRuleCondition(
                                    field = RuleTargetField.BODY,
                                    operator = RuleOperator.CONTAINS,
                                    value = kw,
                                    ignoreCase = true
                                )
                            )
                        }
                        if (includeRegex.isNotBlank()) {
                            add(
                                ForwardingRuleCondition(
                                    field = RuleTargetField.BODY,
                                    operator = RuleOperator.REGEX,
                                    value = includeRegex,
                                    ignoreCase = true
                                )
                            )
                        }
                    }
                } else {
                    conditions
                }

                add(
                    ForwardingRule(
                        id = id,
                        name = name,
                        enabled = enabled,
                        priority = priority,
                        simScope = simScope,
                        conditionRelation = conditionRelation,
                        conditions = resolvedConditions,
                        actions = resolvedActions,
                        customTemplate = customTemplate,
                        regexReplacements = regexReplacements,
                        doNotDisturbEnabled = dndEnabled,
                        dndStart = dndStart,
                        dndEnd = dndEnd,
                        dndDays = dndDays,
                        matchMode = item.optString("matchMode", ForwardingRule.MATCH_ALL),
                        includeKeywords = includeKeywords,
                        excludeKeywords = excludeKeywords,
                        includeRegex = includeRegex,
                        excludeRegex = excludeRegex,
                        channels = channels,
                        targetInstanceIds = targetInstanceIds,
                        timeWindowEnabled = item.optBoolean("timeWindowEnabled", false),
                        timeStart = item.optString("timeStart", "00:00"),
                        timeEnd = item.optString("timeEnd", "23:59"),
                        activeDays = item.optJSONArray("activeDays")?.toIntList() ?: listOf(1, 2, 3, 4, 5, 6, 7)
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

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return listOf(1, 2, 3, 4, 5, 6, 7)
        return buildList {
            for (index in 0 until length()) {
                optInt(index, -1).takeIf { it in 1..7 }?.let(::add)
            }
        }.ifEmpty { listOf(1, 2, 3, 4, 5, 6, 7) }
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
