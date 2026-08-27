package org.fossify.messages.rule.evaluator

import org.fossify.messages.rule.model.ConditionType
import org.fossify.messages.rule.model.IncomingMessageContext
import org.fossify.messages.rule.model.RuleCondition
import org.fossify.messages.rule.model.TargetField
import java.util.Calendar

/**
 * 规则条件求值器 (纯函数，只做条件决策，绝不产生外部副作用)
 */
object ConditionEvaluator {

    fun evaluate(condition: RuleCondition, context: IncomingMessageContext): Boolean {
        return when (condition) {
            is RuleCondition.FieldCondition -> evaluateFieldCondition(condition, context)
            is RuleCondition.TimeRangeCondition -> evaluateTimeRange(condition, context.timestamp)
            is RuleCondition.SimCondition -> condition.subscriptionId == context.subscriptionId
            is RuleCondition.AndCondition -> condition.conditions.all { evaluate(it, context) }
            is RuleCondition.OrCondition -> condition.conditions.any { evaluate(it, context) }
            is RuleCondition.NotCondition -> !evaluate(condition.condition, context)
        }
    }

    private fun evaluateFieldCondition(
        condition: RuleCondition.FieldCondition,
        context: IncomingMessageContext
    ): Boolean {
        val targetText = when (condition.field) {
            TargetField.SENDER -> context.sender
            TargetField.BODY -> context.body
            TargetField.TIME -> context.timestamp.toString()
            TargetField.SIM -> context.subscriptionId.toString()
        }

        return when (condition.operator) {
            ConditionType.MATCH -> targetText.equals(condition.value, ignoreCase = true)
            ConditionType.CONTAINS -> targetText.contains(condition.value, ignoreCase = true)
            ConditionType.REGEX -> runCatching {
                Regex(condition.value, RegexOption.IGNORE_CASE).containsMatchIn(targetText)
            }.getOrDefault(false)
            else -> false
        }
    }

    private fun evaluateTimeRange(
        condition: RuleCondition.TimeRangeCondition,
        timestamp: Long
    ): Boolean {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val startMinutes = condition.startHour * 60 + condition.startMinute
        val endMinutes = condition.endHour * 60 + condition.endMinute

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            // 跨午夜场景 (例如 23:00 至 次日 06:00)
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }
}
