package org.fossify.messages.rule

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.models.OutboxTaskType
import org.fossify.messages.rule.evaluator.ConditionEvaluator
import org.fossify.messages.rule.model.ConditionType
import org.fossify.messages.rule.model.IncomingMessageContext
import org.fossify.messages.rule.model.RuleAction
import org.fossify.messages.rule.model.RuleCondition
import org.fossify.messages.rule.model.RuleDefinition
import org.fossify.messages.rule.model.TargetField
import org.fossify.messages.rule.template.TemplateRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class RuleEngineTest {

    @Test
    fun testKeywordContainsMatching() {
        val condition = RuleCondition.FieldCondition(
            field = TargetField.BODY,
            operator = ConditionType.CONTAINS,
            value = "验证码"
        )

        val matchContext = IncomingMessageContext(
            sender = "10086",
            body = "【中国移动】您的验证码是 654321，5分钟内有效。"
        )
        val nonMatchContext = IncomingMessageContext(
            sender = "10086",
            body = "【中国移动】话费账单提醒。"
        )

        assertTrue(ConditionEvaluator.evaluate(condition, matchContext))
        assertFalse(ConditionEvaluator.evaluate(condition, nonMatchContext))
    }

    @Test
    fun testRegexMatching() {
        val condition = RuleCondition.FieldCondition(
            field = TargetField.SENDER,
            operator = ConditionType.REGEX,
            value = "^1069\\d{4,}$"
        )

        val matchContext = IncomingMessageContext(
            sender = "10690123456",
            body = "通知短信"
        )
        val nonMatchContext = IncomingMessageContext(
            sender = "13800138000",
            body = "通知短信"
        )

        assertTrue(ConditionEvaluator.evaluate(condition, matchContext))
        assertFalse(ConditionEvaluator.evaluate(condition, nonMatchContext))
    }

    @Test
    fun testAndConditionCombination() {
        val andCondition = RuleCondition.AndCondition(
            listOf(
                RuleCondition.FieldCondition(TargetField.SENDER, ConditionType.CONTAINS, "10086"),
                RuleCondition.FieldCondition(TargetField.BODY, ConditionType.CONTAINS, "验证码")
            )
        )

        val fullMatch = IncomingMessageContext(sender = "10086", body = "您的验证码 1234")
        val partialMatch = IncomingMessageContext(sender = "10010", body = "您的验证码 1234")

        assertTrue(ConditionEvaluator.evaluate(andCondition, fullMatch))
        assertFalse(ConditionEvaluator.evaluate(andCondition, partialMatch))
    }

    @Test
    fun testOrConditionCombination() {
        val orCondition = RuleCondition.OrCondition(
            listOf(
                RuleCondition.FieldCondition(TargetField.SENDER, ConditionType.MATCH, "10086"),
                RuleCondition.FieldCondition(TargetField.SENDER, ConditionType.MATCH, "10010")
            )
        )

        assertTrue(ConditionEvaluator.evaluate(orCondition, IncomingMessageContext("10086", "Msg")))
        assertTrue(ConditionEvaluator.evaluate(orCondition, IncomingMessageContext("10010", "Msg")))
        assertFalse(ConditionEvaluator.evaluate(orCondition, IncomingMessageContext("10000", "Msg")))
    }

    @Test
    fun testNotConditionNegation() {
        val notCondition = RuleCondition.NotCondition(
            RuleCondition.FieldCondition(TargetField.BODY, ConditionType.CONTAINS, "广告")
        )

        assertTrue(ConditionEvaluator.evaluate(notCondition, IncomingMessageContext("10086", "正常业务")))
        assertFalse(ConditionEvaluator.evaluate(notCondition, IncomingMessageContext("10086", "本条是广告促销")))
    }

    @Test
    fun testTimeRangeCondition() {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)

        val activeCondition = RuleCondition.TimeRangeCondition(
            startHour = (currentHour - 1 + 24) % 24,
            startMinute = 0,
            endHour = (currentHour + 1) % 24,
            endMinute = 59
        )

        val context = IncomingMessageContext(
            sender = "10086",
            body = "Time test",
            timestamp = calendar.timeInMillis
        )
        assertTrue(ConditionEvaluator.evaluate(activeCondition, context))
    }

    @Test
    fun testSimSlotFiltering() {
        val sim1Condition = RuleCondition.SimCondition(subscriptionId = 1)

        val sim1Context = IncomingMessageContext(sender = "10086", body = "SIM1 Msg", subscriptionId = 1)
        val sim2Context = IncomingMessageContext(sender = "10086", body = "SIM2 Msg", subscriptionId = 2)

        assertTrue(ConditionEvaluator.evaluate(sim1Condition, sim1Context))
        assertFalse(ConditionEvaluator.evaluate(sim1Condition, sim2Context))
    }

    @Test
    fun testTemplateRenderingWithCodeExtraction() {
        val template = "【转发】来自 {{sender}} ({{sim}}): 验证码={{code}}"
        val context = IncomingMessageContext(
            sender = "10698888",
            body = "【工商银行】您尾号8888卡转账验证码是 987654，请勿泄露。",
            subscriptionId = 1
        )

        val rendered = TemplateRenderer.render(template, context)
        assertTrue(rendered.contains("10698888"))
        assertTrue(rendered.contains("987654"))
        assertTrue(rendered.contains("SIM 1"))
    }

    @Test
    fun testRuleEngineEndToEndIntentGeneration() {
        val rule = RuleDefinition(
            ruleId = "rule-code-push",
            name = "验证码推送规则",
            enabled = true,
            priority = 10,
            rootCondition = RuleCondition.FieldCondition(TargetField.BODY, ConditionType.CONTAINS, "验证码"),
            actions = listOf(
                RuleAction(
                    pluginId = "pushplus",
                    targetConfig = mapOf("token" to "test_token_123"),
                    template = "收到验证码: {{code}}"
                )
            )
        )

        val matchedContext = IncomingMessageContext(
            sender = "10086",
            body = "【中国移动】您的验证码为 832941。"
        )
        val intents = RuleEngine.processMessage(matchedContext, listOf(rule))

        assertEquals(1, intents.size)
        val intent = intents.first()
        assertEquals("pushplus", intent.pluginId)
        assertEquals("收到验证码: 832941", intent.renderedContent)

        // Verify Outbox Task conversion
        val outboxContext = RuleEngine.toOutboxTaskContext(intent, "msg-101", "sender-hash", "body-hash")
        assertEquals(OutboxTaskType.FORWARD_PLUGIN, outboxContext.taskType)
        assertTrue(outboxContext.payloadPayload?.contains("pushplus") == true)
        assertTrue(outboxContext.payloadPayload?.contains("832941") == true)
    }

    @Test
    fun testUnmatchedRuleProducesEmptyIntents() {
        val rule = RuleDefinition(
            ruleId = "rule-unmatched",
            name = "严格匹配规则",
            enabled = true,
            rootCondition = RuleCondition.FieldCondition(TargetField.BODY, ConditionType.MATCH, "特定口令"),
            actions = listOf(RuleAction(pluginId = "webhook"))
        )

        val context = IncomingMessageContext(sender = "10086", body = "普通短信内容")
        val intents = RuleEngine.processMessage(context, listOf(rule))

        assertTrue(intents.isEmpty())
    }
}
