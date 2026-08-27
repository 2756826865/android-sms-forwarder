package org.fossify.messages.rule

import org.fossify.messages.models.OutboxSourceType
import org.fossify.messages.models.OutboxTaskContext
import org.fossify.messages.models.OutboxTaskType
import org.fossify.messages.rule.evaluator.ConditionEvaluator
import org.fossify.messages.rule.model.ForwardIntent
import org.fossify.messages.rule.model.IncomingMessageContext
import org.fossify.messages.rule.model.RuleDefinition
import org.fossify.messages.rule.template.TemplateRenderer
import org.json.JSONObject

/**
 * 智能规则引擎 2.0 决策中枢
 *
 * 职责：
 * 1. 过滤已启用的规则并按优先级降序求值；
 * 2. 调度 ConditionEvaluator 进行 AST 条件匹配；
 * 3. 调度 TemplateRenderer 进行动态模板渲染；
 * 4. 产出 ForwardIntent 并提供 Outbox 任务标准转换方法。
 */
object RuleEngine {

    fun processMessage(
        context: IncomingMessageContext,
        rules: List<RuleDefinition>
    ): List<ForwardIntent> {
        val matchedIntents = mutableListOf<ForwardIntent>()

        val activeRules = rules.filter { it.enabled }.sortedByDescending { it.priority }

        for (rule in activeRules) {
            val isMatch = ConditionEvaluator.evaluate(rule.rootCondition, context)
            if (isMatch) {
                for (action in rule.actions) {
                    val renderedText = TemplateRenderer.render(action.template, context)
                    val intent = ForwardIntent(
                        ruleId = rule.ruleId,
                        pluginId = action.pluginId,
                        targetConfig = action.targetConfig,
                        renderedContent = renderedText,
                        priority = action.priority,
                        metadata = mapOf(
                            "ruleName" to rule.name,
                            "simId" to context.subscriptionId.toString(),
                            "sender" to context.sender
                        )
                    )
                    matchedIntents.add(intent)
                }
            }
        }

        return matchedIntents
    }

    /**
     * 将 ForwardIntent 安全转换为 Outbox 任务上下文
     */
    fun toOutboxTaskContext(
        intent: ForwardIntent,
        sourceMessageId: String,
        senderHash: String,
        bodyHash: String
    ): OutboxTaskContext {
        val payloadObj = JSONObject().apply {
            put("pluginId", intent.pluginId)
            put("senderHash", senderHash)
            put("content", intent.renderedContent)
            put("targetConfig", JSONObject(intent.targetConfig))
            put("metadata", JSONObject(intent.metadata))
        }

        return OutboxTaskContext(
            taskType = OutboxTaskType.FORWARD_PLUGIN,
            sourceType = OutboxSourceType.FORWARDING_RULE,
            sourceId = sourceMessageId,
            rawPayload = intent.renderedContent,
            payloadPayload = payloadObj.toString(),
            maxAttempts = 3,
            initialDelayMs = 0L
        )
    }
}
