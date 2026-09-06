package org.fossify.messages

import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingRule
import org.fossify.messages.forwarding.ForwardingRuleAction
import org.fossify.messages.forwarding.ForwardingRuleCondition
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.RegexReplacement
import org.fossify.messages.forwarding.RuleConditionRelation
import org.fossify.messages.forwarding.RuleOperator
import org.fossify.messages.forwarding.RuleTargetField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ForwardingRulesV2Test {

    @Test
    fun testNewRuleDefaults() {
        val rule = ForwardingRule(name = "测试默认值")
        assertFalse(rule.enabled)
        assertTrue(rule.channels.isEmpty())
        assertTrue(rule.targetInstanceIds.isEmpty())
        assertTrue(rule.actions.isEmpty())
        assertTrue(rule.conditions.isEmpty())
    }

    @Test
    fun testEmptyTargetsNeverSend() {
        val rule = ForwardingRule(
            name = "无目标规则",
            enabled = true,
            conditions = listOf(
                ForwardingRuleCondition(
                    field = RuleTargetField.BODY,
                    operator = RuleOperator.CONTAINS,
                    value = "验证码"
                )
            ),
            actions = emptyList(),
            channels = emptyList()
        )
        val engine = ForwardingRuleEngine(listOf(rule))
        val decision = engine.evaluate(
            sender = "10086",
            body = "您的验证码是 123456",
            subscriptionId = 0,
            channelCandidates = setOf("dingtalk", "wecom_bot")
        )
        assertTrue("空目标集合必须不发送", decision.targets.isEmpty())
        assertTrue("允许渠道必须为空", decision.allowedChannels.isEmpty())
    }

    @Test
    fun testSenderVsBodyMatching() {
        val ruleSenderOnly = ForwardingRule(
            name = "仅发件人规则",
            enabled = true,
            conditions = listOf(
                ForwardingRuleCondition(
                    field = RuleTargetField.SENDER,
                    operator = RuleOperator.EQUALS,
                    value = "95588"
                )
            ),
            actions = listOf(
                ForwardingRuleAction(
                    channelType = "dingtalk",
                    targetInstanceId = "inst_dingtalk_1"
                )
            )
        )
        val engine = ForwardingRuleEngine(listOf(ruleSenderOnly))

        // 发件人命中
        val decision1 = engine.evaluate("95588", "工行通知", 0, setOf("dingtalk"))
        assertEquals(1, decision1.targets.size)
        assertEquals("inst_dingtalk_1", decision1.targets.first().instanceId)

        // 正文中出现 95588 但发件人是 10086 -> 必须不命中！
        val decision2 = engine.evaluate("10086", "工行电话 95588", 0, setOf("dingtalk"))
        assertTrue("发件人条件不应被正文内容误命中", decision2.targets.isEmpty())
    }

    @Test
    fun testConditionOperators() {
        val ruleContains = ForwardingRule(
            name = "CONTAINS测试",
            enabled = true,
            conditions = listOf(
                ForwardingRuleCondition(
                    field = RuleTargetField.BODY,
                    operator = RuleOperator.CONTAINS,
                    value = "重要通知"
                )
            ),
            actions = listOf(ForwardingRuleAction(channelType = "bark", targetInstanceId = "inst_bark"))
        )
        val engine = ForwardingRuleEngine(listOf(ruleContains))
        assertTrue(engine.evaluate("10086", "这是一条重要通知信息", 0, setOf("bark")).targets.isNotEmpty())
        assertTrue(engine.evaluate("10086", "普通短信", 0, setOf("bark")).targets.isEmpty())

        val ruleRegex = ForwardingRule(
            name = "REGEX测试",
            enabled = true,
            conditions = listOf(
                ForwardingRuleCondition(
                    field = RuleTargetField.BODY,
                    operator = RuleOperator.REGEX,
                    value = "验证码[:：]?\\s*\\d{6}"
                )
            ),
            actions = listOf(ForwardingRuleAction(channelType = "bark", targetInstanceId = "inst_bark"))
        )
        val engineRegex = ForwardingRuleEngine(listOf(ruleRegex))
        assertTrue(engineRegex.evaluate("10086", "您的动态验证码：889922 请妥善保管", 0, setOf("bark")).targets.isNotEmpty())
        assertTrue(engineRegex.evaluate("10086", "验证码是四位1234", 0, setOf("bark")).targets.isEmpty())
    }

    @Test
    fun testConditionRelationAllAndAny() {
        val cond1 = ForwardingRuleCondition(field = RuleTargetField.SENDER, operator = RuleOperator.EQUALS, value = "10086")
        val cond2 = ForwardingRuleCondition(field = RuleTargetField.BODY, operator = RuleOperator.CONTAINS, value = "账单")

        val ruleAll = ForwardingRule(
            name = "AND测试",
            enabled = true,
            conditionRelation = RuleConditionRelation.ALL,
            conditions = listOf(cond1, cond2),
            actions = listOf(ForwardingRuleAction(channelType = "telegram", targetInstanceId = "inst_tg"))
        )
        val engineAll = ForwardingRuleEngine(listOf(ruleAll))
        // 满足一个不满足另一个 -> 失败
        assertTrue(engineAll.evaluate("10086", "余额不足", 0, setOf("telegram")).targets.isEmpty())
        // 两个都满足 -> 成功
        assertEquals(1, engineAll.evaluate("10086", "您的本月账单已出", 0, setOf("telegram")).targets.size)

        val ruleAny = ForwardingRule(
            name = "OR测试",
            enabled = true,
            conditionRelation = RuleConditionRelation.ANY,
            conditions = listOf(cond1, cond2),
            actions = listOf(ForwardingRuleAction(channelType = "telegram", targetInstanceId = "inst_tg"))
        )
        val engineAny = ForwardingRuleEngine(listOf(ruleAny))
        assertEquals(1, engineAny.evaluate("10086", "余额不足", 0, setOf("telegram")).targets.size)
        assertEquals(1, engineAny.evaluate("10010", "您的本月账单已出", 0, setOf("telegram")).targets.size)
    }

    @Test
    fun testSimSlotMatching() {
        val ruleSim1 = ForwardingRule(
            name = "SIM1规则",
            enabled = true,
            simScope = ForwardingRule.SIM_1,
            conditions = listOf(ForwardingRuleCondition(field = RuleTargetField.BODY, operator = RuleOperator.CONTAINS, value = "卡1")),
            actions = listOf(ForwardingRuleAction(channelType = "feishu_bot", targetInstanceId = "inst_feishu"))
        )
        val engine = ForwardingRuleEngine(listOf(ruleSim1))

        // slot 0 (SIM1) -> 命中
        assertEquals(1, engine.evaluate("10086", "卡1消息", 0, setOf("feishu_bot"), simSlotIndex = 0).targets.size)
        // slot 1 (SIM2) -> 必须被卡槽过滤拦截！
        assertTrue(engine.evaluate("10086", "卡1消息", 0, setOf("feishu_bot"), simSlotIndex = 1).targets.isEmpty())
    }

    @Test
    fun testOrderedRegexReplacement() {
        val replacements = listOf(
            RegexReplacement(pattern = "验证码[:：]?\\s*(\\d{6})", replacement = "OTP:[$1]"),
            RegexReplacement(pattern = "退订回T", replacement = "")
        )
        val engine = ForwardingRuleEngine(emptyList())

        val raw = "【测试行】您的动态验证码：951332，请勿泄露。退订回T"
        val replaced = engine.applyRegexReplacements(raw, replacements)
        assertEquals("【测试行】您的动态OTP:[951332]，请勿泄露。", replaced)
    }

    @Test
    fun testDoNotDisturbCrossMidnight() {
        val engine = ForwardingRuleEngine(emptyList())

        // 免打扰：22:00 ~ 06:00
        val dndStart = "22:00"
        val dndEnd = "06:00"
        val allDays = listOf(1, 2, 3, 4, 5, 6, 7)

        // 模拟晚上 23:30 (处于免打扰)
        val calNight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 30)
        }
        assertTrue("23:30 应处于 22:00~06:00 免打扰内", engine.isTimeRangeActive(dndStart, dndEnd, allDays, calNight.timeInMillis))

        // 模拟凌晨 03:15 (处于免打扰)
        val calEarly = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 3)
            set(Calendar.MINUTE, 15)
        }
        assertTrue("03:15 应处于 22:00~06:00 免打扰内", engine.isTimeRangeActive(dndStart, dndEnd, allDays, calEarly.timeInMillis))

        // 模拟白天 14:00 (正常工作，不在免打扰内)
        val calDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 0)
        }
        assertFalse("14:00 不应处于免打扰内", engine.isTimeRangeActive(dndStart, dndEnd, allDays, calDay.timeInMillis))
    }

    @Test
    fun testInstanceLevelPreciseTargeting() {
        // 创建两条规则分别指向两个不同钉钉机器人实例
        val ruleA = ForwardingRule(
            name = "研发告警",
            enabled = true,
            conditions = listOf(ForwardingRuleCondition(field = RuleTargetField.BODY, operator = RuleOperator.CONTAINS, value = "服务器告警")),
            actions = listOf(ForwardingRuleAction(channelType = "dingtalk", targetInstanceId = "dingtalk_dev_cluster"))
        )
        val ruleB = ForwardingRule(
            name = "家庭账单",
            enabled = true,
            conditions = listOf(ForwardingRuleCondition(field = RuleTargetField.BODY, operator = RuleOperator.CONTAINS, value = "水费账单")),
            actions = listOf(ForwardingRuleAction(channelType = "dingtalk", targetInstanceId = "dingtalk_family"))
        )
        val engine = ForwardingRuleEngine(listOf(ruleA, ruleB))

        val decisionA = engine.evaluate("10086", "【紧急】服务器告警 CPU 99%", 0, setOf("dingtalk"))
        assertEquals(1, decisionA.targets.size)
        assertEquals("dingtalk_dev_cluster", decisionA.targets.first().instanceId)
        assertFalse("绝对不能投递到未选中的实例B", decisionA.targets.any { it.instanceId == "dingtalk_family" })
    }
}
