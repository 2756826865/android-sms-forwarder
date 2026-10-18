package org.fossify.messages.forwarding

import org.junit.Assert.*
import org.junit.Test

class SimLabelPresentationTest {
    @Test fun customNamesKeepTheirPhysicalSlot() {
        assertEquals("SIM1 · 工作号1234", SimLabelPresentation.describeSlot(0, "工作号1234", "中国移动"))
        assertEquals("SIM2 · 家人号1234", SimLabelPresentation.describeSlot(1, "家人号1234"))
    }

    @Test fun alreadyPrefixedNamesAreNotDuplicated() {
        for (name in listOf("SIM1 · 工作号1234", "sim1·工作号1234", "SIM1 工作号1234")) {
            assertEquals("SIM1 · 工作号1234", SimLabelPresentation.describeSlot(0, name))
        }
        assertEquals("SIM1", SimLabelPresentation.describeSlot(0, "SIM1"))
    }

    @Test fun emptyNamesRetainCarrierAndUnknownSlotsNeverGuess() {
        assertEquals("SIM2 · 中国电信", SimLabelPresentation.describeSlot(1, "", "中国电信"))
        assertEquals("SIM1", SimLabelPresentation.describeSlot(0, ""))
        assertEquals("未知接收卡", SimLabelPresentation.describeSlot(-1, "工作号1234"))
    }

    @Test fun builtInBodyGetsOneLabel() {
        val once = SimLabelPresentation.addLabel("验证码 1234", "工作卡", false)
        assertEquals("【工作卡】\n验证码 1234", once)
        assertEquals(once, SimLabelPresentation.addLabel(once, "工作卡", false))
    }

    @Test fun detailedAndEmojiTemplatesDoNotDuplicateTheirSimLine() {
        for (body in listOf("内容\n卡槽：工作卡", "内容\n📶卡槽：工作卡", "内容\n 【工作卡】 ")) {
            assertEquals(body, SimLabelPresentation.addLabel(body, "工作卡", false))
        }
    }

    @Test fun explicitTemplatesRetainTheirExactBodyEvenWithoutASimTag() {
        for (rendered in listOf(false, true)) {
            for (allow in listOf(false, true)) {
                assertTrue(SimLabelPresentation.preserveBody(rendered, allow, true))
            }
        }
        assertTrue(SimLabelPresentation.preserveBody(true, false, false))
    }

    @Test fun renderedGlobalRuleIsDifferentFromAnExplicitTemplate() {
        assertFalse(SimLabelPresentation.preserveBody(true, true, false))
        assertFalse(SimLabelPresentation.preserveBody(false, false, false))
    }

    @Test fun noResolvedLabelNeverGuessesAnotherCard() {
        assertEquals("内容", SimLabelPresentation.addLabel("内容", "", false))
        assertEquals("内容", SimLabelPresentation.addLabel("内容", "  ", false))
    }

    @Test fun addedLabelHonorsPhoneMaskingAndDoesNotUnmaskTheBody() {
        val body = "验证码 [******]，电话 139****1234"
        val rendered = SimLabelPresentation.addLabel(body, "工作卡13800138000", true)
        assertEquals("【工作卡138****8000】\n$body", rendered)
        assertFalse(rendered.contains("13800138000"))
        assertEquals(rendered, SimLabelPresentation.addLabel(rendered, "工作卡13800138000", true))
    }

    @Test fun privacyOffPreservesLabelExactly() {
        assertEquals("【13800138000】\n内容", SimLabelPresentation.addLabel("内容", "13800138000", false))
    }

    private fun target(customMode: Boolean = false, inherited: String = ""): ForwardingTarget {
        val action = ForwardingRuleAction(channelType = "dingtalk", targetInstanceId = "instance",
            templateMode = if (customMode) RuleTemplateMode.CUSTOM else RuleTemplateMode.GLOBAL)
        val rule = ForwardingRule(name = "test", enabled = true, customTemplate = inherited, actions = listOf(action),
            conditions = listOf(ForwardingRuleCondition(field = RuleTargetField.BODY, operator = RuleOperator.CONTAINS, value = "hello")))
        return ForwardingRuleEngine(listOf(rule)).evaluate("10086", "hello", 42,
            setOf("dingtalk"), resolveContent = { _, _, _ -> "rendered" }).targets.single()
    }

    @Test fun ordinaryRuleOptsIntoTransportLabel() {
        assertTrue(target().allowSimLabelPrefix)
    }

    @Test fun actionCustomEvenEmptyNeverOptsIntoTransportLabel() {
        assertFalse(target(customMode = true).allowSimLabelPrefix)
    }

    @Test fun inheritedRuleTemplateNeverOptsIntoTransportLabel() {
        assertFalse(target(inherited = "{{SMS}}").allowSimLabelPrefix)
    }

    @Test fun legacyChannelAndInstanceTargetsUseTheSameRenderer() {
        for (instance in listOf(false, true)) {
            var calls = 0
            val rule = ForwardingRule(name = "legacy", enabled = true, customTemplate = "{{CARD_SLOT}}:{{SMS}}",
                conditions = listOf(ForwardingRuleCondition(field = RuleTargetField.BODY, operator = RuleOperator.CONTAINS, value = "hello")),
                channels = if (instance) emptyList() else listOf("dingtalk"),
                targetInstanceIds = if (instance) listOf("instance") else emptyList())
            val target = ForwardingRuleEngine(listOf(rule)).evaluate("10086", "hello", 42,
                setOf("dingtalk"), resolveContent = { _, _, _ -> calls++; "工作卡:hello" }).targets.single()
            assertEquals(1, calls)
            assertEquals("工作卡:hello", target.renderedContent)
            assertFalse(target.allowSimLabelPrefix)
        }
    }
}
