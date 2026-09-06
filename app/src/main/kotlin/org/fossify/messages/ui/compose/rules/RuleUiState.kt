package org.fossify.messages.ui.compose.rules

import org.fossify.messages.forwarding.ForwardingChannelInstance
import org.fossify.messages.forwarding.ForwardingRule
import org.fossify.messages.forwarding.ForwardingRuleAction
import org.fossify.messages.forwarding.ForwardingRuleCondition
import org.fossify.messages.forwarding.RegexReplacement
import org.fossify.messages.forwarding.RuleConditionRelation

data class RuleListUiState(
    val rules: List<ForwardingRule> = emptyList(),
    val isRulesEnabled: Boolean = false,
    val scope: Int = 0,
    val lastDecision: String = "",
    val availableInstances: List<ForwardingChannelInstance> = emptyList()
)

data class RuleEditorUiState(
    val ruleId: String = "",
    val isNew: Boolean = true,
    val name: String = "",
    val enabled: Boolean = false,
    val priority: Int = 0,
    val simScope: String = ForwardingRule.SIM_ALL,
    val conditionRelation: RuleConditionRelation = RuleConditionRelation.ALL,
    val conditions: List<ForwardingRuleCondition> = emptyList(),
    val actions: List<ForwardingRuleAction> = emptyList(),
    val customTemplate: String = "",
    val regexReplacements: List<RegexReplacement> = emptyList(),
    val doNotDisturbEnabled: Boolean = false,
    val dndStart: String = "22:00",
    val dndEnd: String = "06:00",
    val dndDays: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),

    // 字段校验错误提示
    val nameError: String? = null,
    val conditionError: String? = null,
    val actionError: String? = null,
    val dndError: String? = null,

    // 用户已保存的通道实例池
    val availableInstances: List<ForwardingChannelInstance> = emptyList(),

    // 实时测试状态
    val testSender: String = "10086",
    val testBody: String = "【中国移动】您的验证码是 951332，请在 5 分钟内完成验证。",
    val testSimSlot: Int? = 0,
    val testResultSummary: String? = null,
    val testRenderedContent: String? = null,
    val isSaving: Boolean = false,
    val isSavedSuccess: Boolean = false
)
