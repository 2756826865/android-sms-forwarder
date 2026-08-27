package org.fossify.messages.rule.model

/**
 * 待匹配短信上下文
 */
data class IncomingMessageContext(
    val sender: String,
    val body: String,
    val subscriptionId: Int = -1,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 条件操作符类型
 */
enum class ConditionType {
    AND,
    OR,
    NOT,
    MATCH,       // 完全匹配
    CONTAINS,    // 包含
    REGEX,       // 正则表达式
    TIME_RANGE,  // 时间窗口
    SIM_MATCH    // SIM 卡槽匹配
}

/**
 * 目标字段
 */
enum class TargetField {
    SENDER,
    BODY,
    TIME,
    SIM
}

/**
 * 规则条件抽象语法树 (AST)
 */
sealed class RuleCondition {
    data class FieldCondition(
        val field: TargetField,
        val operator: ConditionType,
        val value: String
    ) : RuleCondition()

    data class TimeRangeCondition(
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int
    ) : RuleCondition()

    data class SimCondition(
        val subscriptionId: Int
    ) : RuleCondition()

    data class AndCondition(
        val conditions: List<RuleCondition>
    ) : RuleCondition()

    data class OrCondition(
        val conditions: List<RuleCondition>
    ) : RuleCondition()

    data class NotCondition(
        val condition: RuleCondition
    ) : RuleCondition()
}

/**
 * 规则命中后的投递动作配置
 */
data class RuleAction(
    val pluginId: String,
    val targetConfig: Map<String, String> = emptyMap(),
    val template: String? = null,
    val priority: Int = 0
)

/**
 * 完整规则定义
 */
data class RuleDefinition(
    val ruleId: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val rootCondition: RuleCondition,
    val actions: List<RuleAction> = emptyList()
)

/**
 * 规则命中生成的投递意图 (Forward Intent)
 */
data class ForwardIntent(
    val ruleId: String,
    val pluginId: String,
    val targetConfig: Map<String, String>,
    val renderedContent: String,
    val priority: Int = 0,
    val metadata: Map<String, String> = emptyMap()
)
