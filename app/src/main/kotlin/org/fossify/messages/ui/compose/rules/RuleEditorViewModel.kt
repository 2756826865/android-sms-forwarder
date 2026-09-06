package org.fossify.messages.ui.compose.rules

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.fossify.messages.forwarding.ForwardingMessageFormatter
import org.fossify.messages.forwarding.ForwardingRule
import org.fossify.messages.forwarding.ForwardingRuleAction
import org.fossify.messages.forwarding.ForwardingRuleCondition
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.RegexReplacement
import org.fossify.messages.forwarding.RuleConditionRelation
import org.fossify.messages.forwarding.RuleOperator
import org.fossify.messages.forwarding.RuleTargetField
import org.fossify.messages.forwarding.RuleTemplateMode
import org.fossify.messages.forwarding.repository.RuleRepository
import org.fossify.messages.forwarding.repository.ChannelRepository
import java.util.UUID

class RuleEditorViewModel(
    private val context: Context,
    private val ruleId: String? = null
) : ViewModel() {

    private val repository = RuleRepository.getInstance(context)
    private val channelRepository = ChannelRepository.getInstance(context)

    private val _uiState = MutableStateFlow(RuleEditorUiState())
    val uiState: StateFlow<RuleEditorUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        // 规则必须能看见已绑定但暂时停用的实例，避免停用后名称丢失或无法解除关联。
        val instances = channelRepository.getInstances()
        if (!ruleId.isNullOrBlank()) {
            val existing = repository.getRuleById(ruleId)
            if (existing != null) {
                _uiState.update {
                    it.copy(
                        ruleId = existing.id,
                        isNew = false,
                        name = existing.name,
                        enabled = existing.enabled,
                        priority = existing.priority,
                        simScope = existing.simScope,
                        conditionRelation = existing.conditionRelation,
                        conditions = existing.conditions,
                        actions = existing.actions,
                        customTemplate = existing.customTemplate,
                        regexReplacements = existing.regexReplacements,
                        doNotDisturbEnabled = existing.doNotDisturbEnabled,
                        dndStart = existing.dndStart,
                        dndEnd = existing.dndEnd,
                        dndDays = existing.dndDays,
                        availableInstances = instances
                    )
                }
                runLiveTest()
                return
            }
        }

        // 新建规则：默认无通道、无动作、严格等待用户主动添加
        _uiState.update {
            it.copy(
                ruleId = UUID.randomUUID().toString(),
                isNew = true,
                name = "",
                enabled = false,
                simScope = ForwardingRule.SIM_ALL,
                conditions = emptyList(),
                actions = emptyList(),
                availableInstances = instances
            )
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name, nameError = null) }
    }

    fun updateEnabled(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
    }

    fun updatePriority(priority: Int) {
        _uiState.update { it.copy(priority = priority) }
    }

    fun updateSimScope(simScope: String) {
        _uiState.update { it.copy(simScope = simScope) }
        runLiveTest()
    }

    fun updateConditionRelation(relation: RuleConditionRelation) {
        _uiState.update { it.copy(conditionRelation = relation) }
        runLiveTest()
    }

    fun addCondition(field: RuleTargetField = RuleTargetField.BODY, operator: RuleOperator = RuleOperator.CONTAINS, value: String = "") {
        val newCond = ForwardingRuleCondition(
            id = UUID.randomUUID().toString(),
            field = field,
            operator = operator,
            value = value,
            ignoreCase = true,
            enabled = true
        )
        _uiState.update {
            it.copy(conditions = it.conditions + newCond, conditionError = null)
        }
        runLiveTest()
    }

    fun updateCondition(id: String, field: RuleTargetField, operator: RuleOperator, value: String, ignoreCase: Boolean) {
        _uiState.update { state ->
            val updated = state.conditions.map { cond ->
                if (cond.id == id) {
                    cond.copy(field = field, operator = operator, value = value, ignoreCase = ignoreCase)
                } else cond
            }
            state.copy(conditions = updated, conditionError = null)
        }
        runLiveTest()
    }

    fun removeCondition(id: String) {
        _uiState.update { state ->
            state.copy(conditions = state.conditions.filterNot { it.id == id })
        }
        runLiveTest()
    }

    fun addAction(instanceId: String) {
        val inst = _uiState.value.availableInstances.firstOrNull { it.id == instanceId } ?: return
        val newAction = ForwardingRuleAction(
            id = UUID.randomUUID().toString(),
            channelType = inst.channelType,
            targetInstanceId = inst.id,
            enabled = true,
            templateMode = RuleTemplateMode.GLOBAL
        )
        _uiState.update { state ->
            if (state.actions.any { it.targetInstanceId == instanceId }) return@update state
            state.copy(actions = state.actions + newAction, actionError = null)
        }
        runLiveTest()
    }

    fun removeAction(actionId: String) {
        _uiState.update { state ->
            state.copy(actions = state.actions.filterNot { it.id == actionId })
        }
        runLiveTest()
    }

    fun updateCustomTemplate(template: String) {
        _uiState.update { it.copy(customTemplate = template) }
        runLiveTest()
    }

    fun addRegexReplacement(pattern: String = "", replacement: String = "", ignoreCase: Boolean = true) {
        val item = RegexReplacement(pattern, replacement, ignoreCase)
        _uiState.update { it.copy(regexReplacements = it.regexReplacements + item) }
        runLiveTest()
    }

    fun updateRegexReplacement(index: Int, pattern: String, replacement: String, ignoreCase: Boolean) {
        _uiState.update { state ->
            val list = state.regexReplacements.toMutableList()
            if (index in 0 until list.size) {
                list[index] = RegexReplacement(pattern, replacement, ignoreCase)
            }
            state.copy(regexReplacements = list)
        }
        runLiveTest()
    }

    fun removeRegexReplacement(index: Int) {
        _uiState.update { state ->
            val list = state.regexReplacements.toMutableList()
            if (index in 0 until list.size) {
                list.removeAt(index)
            }
            state.copy(regexReplacements = list)
        }
        runLiveTest()
    }

    fun updateDoNotDisturb(enabled: Boolean, start: String, end: String, days: List<Int>) {
        _uiState.update {
            it.copy(
                doNotDisturbEnabled = enabled,
                dndStart = start,
                dndEnd = end,
                dndDays = days,
                dndError = null
            )
        }
        runLiveTest()
    }

    fun updateTestInputs(sender: String, body: String, simSlot: Int?) {
        _uiState.update {
            it.copy(testSender = sender, testBody = body, testSimSlot = simSlot)
        }
        runLiveTest()
    }

    fun runLiveTest() {
        val state = _uiState.value
        val draftRule = buildRuleFromState()

        val engine = ForwardingRuleEngine(listOf(draftRule.copy(enabled = true)))
        val candidates = channelRepository.getEnabledInstances()
            .map { it.channelType }
            .toSet()
        val decision = engine.evaluate(
            sender = state.testSender,
            body = state.testBody,
            subscriptionId = -1,
            channelCandidates = candidates,
            simSlotIndex = state.testSimSlot,
            resolveContent = { rule, action, text ->
                val template = action.customTemplate.takeIf { action.templateMode == RuleTemplateMode.CUSTOM }
                    ?: rule.customTemplate.takeIf(String::isNotBlank)
                if (template != null) {
                    ForwardingMessageFormatter.renderRuleTemplate(context, template, state.testSender, text, System.currentTimeMillis(), state.testSimSlot ?: -1)
                } else {
                    ForwardingMessageFormatter.format(context, state.testSender, text, System.currentTimeMillis(), state.testSimSlot ?: -1).content
                }
            }
        )

        val summary = if (decision.targets.isNotEmpty()) {
            val names = decision.targets.map { t ->
                state.availableInstances.firstOrNull { it.id == t.instanceId }?.name ?: t.channelType
            }.joinToString("、")
            "✅ 匹配成功！命中 ${decision.targets.size} 个通道实例：$names"
        } else {
            "❌ 未命中：${decision.reason}"
        }

        val rendered = decision.targets.firstOrNull()?.renderedContent ?: engine.applyRegexReplacements(state.testBody, draftRule.regexReplacements)

        _uiState.update {
            it.copy(testResultSummary = summary, testRenderedContent = rendered)
        }
    }

    fun saveRule(): Boolean {
        val state = _uiState.value
        var hasError = false

        var nameErr: String? = null
        if (state.name.isBlank()) {
            nameErr = "规则名称不能为空"
            hasError = true
        }

        var condErr: String? = null
        if (state.conditions.isEmpty()) {
            condErr = "至少需要添加一个匹配条件"
            hasError = true
        } else {
            for (cond in state.conditions) {
                if (cond.value.isBlank()) {
                    condErr = "条件值不能为空"
                    hasError = true
                    break
                }
                if (cond.operator == RuleOperator.REGEX) {
                    val isValid = runCatching { Regex(cond.value) }.isSuccess
                    if (!isValid) {
                        condErr = "正则表达式格式有误：${cond.value}"
                        hasError = true
                        break
                    }
                }
            }
        }

        var actErr: String? = null
        if (state.actions.isEmpty()) {
            actErr = "至少需要选择一个发送目标通道实例"
            hasError = true
        }

        if (hasError) {
            _uiState.update {
                it.copy(nameError = nameErr, conditionError = condErr, actionError = actErr)
            }
            return false
        }

        val ruleToSave = buildRuleFromState()
        repository.saveRule(ruleToSave)
        _uiState.update { it.copy(isSavedSuccess = true) }
        return true
    }

    private fun buildRuleFromState(): ForwardingRule {
        val state = _uiState.value
        return ForwardingRule(
            id = state.ruleId,
            name = state.name.trim(),
            enabled = state.enabled,
            priority = state.priority,
            simScope = state.simScope,
            conditionRelation = state.conditionRelation,
            conditions = state.conditions,
            actions = state.actions,
            customTemplate = state.customTemplate.trim(),
            regexReplacements = state.regexReplacements,
            doNotDisturbEnabled = state.doNotDisturbEnabled,
            dndStart = state.dndStart,
            dndEnd = state.dndEnd,
            dndDays = state.dndDays
        )
    }
}
