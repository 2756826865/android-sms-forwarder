package org.fossify.messages.activities

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityForwardingRulesSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.bindMiuiOptions
import org.fossify.messages.extensions.showSmsStyled
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingRule
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig

class ForwardingRulesSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityForwardingRulesSettingsBinding::inflate)
    private val config by lazy { ForwardingRulesConfig(applicationContext) }
    private var workingRules = mutableListOf<ForwardingRule>()
    private var selectedRuleIndex = 0
    private var selectedChannels = ForwardingChannels.allRuleChannels.toMutableSet()
    private var loadingEditor = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.rulesAppbar),
            padBottomImeAndSystem = listOf(binding.rulesScrollview),
        )
        setupMaterialScrollListener(binding.rulesScrollview, binding.rulesAppbar)
        setupTopAppBar(binding.rulesAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.rulesAppbar, binding.rulesToolbar)
        binding.rulesScope.bindMiuiOptions(R.array.forwarding_rules_scope_options)
        binding.rulesSimScope.bindMiuiOptions(R.array.forwarding_rules_sim_options)
        binding.rulesMatchMode.bindMiuiOptions(R.array.forwarding_rules_match_options)
        loadRules()

        binding.rulesChannels.setOnClickListener { showChannelSelector() }
        binding.rulesTest.setOnClickListener { testRule() }
        binding.rulesAdd.setOnClickListener { addRule() }
        binding.rulesDelete.setOnClickListener { deleteRule() }
        binding.rulesMoveUp.setOnClickListener { moveRule(-1) }
        binding.rulesMoveDown.setOnClickListener { moveRule(1) }
        binding.rulesSave.setOnClickListener { saveAll() }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.rulesAppbar, binding.rulesToolbar)
    }

    private fun loadRules() {
        binding.rulesEnabled.isChecked = config.enabled
        binding.rulesScope.setSelection(config.scope.coerceIn(0, 2))
        binding.rulesTestBody.setText(getString(R.string.forwarding_rules_test_body_default))
        binding.rulesLastDecision.text = config.lastDecision
            .takeIf(String::isNotBlank)
            ?.let { getString(R.string.forwarding_rules_last_decision, it) }
            ?: getString(R.string.forwarding_rules_last_decision_empty)
        workingRules = config.rules.toMutableList().ifEmpty { mutableListOf(newRule(1)) }
        selectedRuleIndex = 0
        loadEditor(workingRules.first())
        renderRuleList()
    }

    private fun renderRuleList() {
        binding.rulesList.removeAllViews()
        val density = resources.displayMetrics.density
        workingRules.forEachIndexed { index, rule ->
            binding.rulesList.addView(MaterialButton(this).apply {
                isAllCaps = false
                text = "${index + 1}. ${rule.name} · ${if (rule.enabled) "已启用" else "已关闭"}"
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = (52 * density).toInt()
                insetTop = 0
                insetBottom = 0
                setTypeface(typeface, if (index == selectedRuleIndex) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(ContextCompat.getColor(context, R.color.miui_primary_text))
                backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        context,
                        if (index == selectedRuleIndex) R.color.miui_selected_background else R.color.miui_card_background,
                    ),
                )
                strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.bulk_outline))
                strokeWidth = (density).toInt().coerceAtLeast(1)
                cornerRadius = (8 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (6 * density).toInt() }
                setOnClickListener { selectRule(index) }
            })
        }
        binding.rulesDelete.isEnabled = workingRules.isNotEmpty()
        binding.rulesMoveUp.isEnabled = selectedRuleIndex > 0
        binding.rulesMoveDown.isEnabled = selectedRuleIndex < workingRules.lastIndex
    }

    private fun selectRule(index: Int) {
        if (index !in workingRules.indices || index == selectedRuleIndex) return
        val current = readEditor() ?: return
        workingRules[selectedRuleIndex] = current
        selectedRuleIndex = index
        loadEditor(workingRules[index])
        renderRuleList()
    }

    private fun loadEditor(rule: ForwardingRule) {
        loadingEditor = true
        binding.rulesName.setText(rule.name)
        binding.ruleItemEnabled.isChecked = rule.enabled
        binding.rulesMatchMode.setSelection(if (rule.matchMode == ForwardingRule.MATCH_ANY) 1 else 0)
        binding.rulesSimScope.setSelection(
            when (rule.simScope) {
                ForwardingRule.SIM_1 -> 1
                ForwardingRule.SIM_2 -> 2
                else -> 0
            },
        )
        binding.rulesAllowKeywords.setText(rule.includeKeywords.joinToString("\n"))
        binding.rulesExcludeKeywords.setText(rule.excludeKeywords.joinToString("\n"))
        binding.rulesAllowRegex.setText(rule.includeRegex)
        binding.rulesExcludeRegex.setText(rule.excludeRegex)
        selectedChannels = rule.channels.toMutableSet()
        updateChannelSummary()
        loadingEditor = false
    }

    private fun readEditor(showError: Boolean = true): ForwardingRule? {
        if (loadingEditor) return workingRules.getOrNull(selectedRuleIndex)
        val includeRegex = binding.rulesAllowRegex.text?.toString().orEmpty().trim()
        val excludeRegex = binding.rulesExcludeRegex.text?.toString().orEmpty().trim()
        if (!isRegexValid(includeRegex) || !isRegexValid(excludeRegex)) {
            if (showError) toast(R.string.forwarding_rules_invalid_regex)
            return null
        }
        val fallbackName = getString(R.string.forwarding_rules_rule_default, selectedRuleIndex + 1)
        return ForwardingRule(
            name = binding.rulesName.text?.toString().orEmpty().trim().ifBlank { fallbackName },
            enabled = binding.ruleItemEnabled.isChecked,
            matchMode = if (binding.rulesMatchMode.selectedItemPosition == 1) {
                ForwardingRule.MATCH_ANY
            } else {
                ForwardingRule.MATCH_ALL
            },
            simScope = when (binding.rulesSimScope.selectedItemPosition) {
                1 -> ForwardingRule.SIM_1
                2 -> ForwardingRule.SIM_2
                else -> ForwardingRule.SIM_ALL
            },
            includeKeywords = splitValues(binding.rulesAllowKeywords.text?.toString().orEmpty()),
            excludeKeywords = splitValues(binding.rulesExcludeKeywords.text?.toString().orEmpty()),
            includeRegex = includeRegex,
            excludeRegex = excludeRegex,
            channels = selectedChannels.toList(),
        )
    }

    private fun addRule() {
        val current = readEditor() ?: return
        workingRules[selectedRuleIndex] = current
        workingRules += newRule(workingRules.size + 1)
        selectedRuleIndex = workingRules.lastIndex
        loadEditor(workingRules.last())
        renderRuleList()
    }

    private fun deleteRule() {
        if (workingRules.isEmpty()) return
        workingRules.removeAt(selectedRuleIndex)
        if (workingRules.isEmpty()) workingRules += newRule(1)
        selectedRuleIndex = selectedRuleIndex.coerceAtMost(workingRules.lastIndex)
        loadEditor(workingRules[selectedRuleIndex])
        renderRuleList()
    }

    private fun moveRule(offset: Int) {
        val target = selectedRuleIndex + offset
        if (target !in workingRules.indices) return
        val current = readEditor() ?: return
        workingRules[selectedRuleIndex] = current
        val moved = workingRules.removeAt(selectedRuleIndex)
        workingRules.add(target, moved)
        selectedRuleIndex = target
        loadEditor(workingRules[selectedRuleIndex])
        renderRuleList()
    }

    private fun saveAll() {
        val current = readEditor(showError = binding.rulesEnabled.isChecked)
        if (current == null) {
            if (!binding.rulesEnabled.isChecked) {
                config.enabled = false
                toast(R.string.forwarding_saved)
            }
            return
        }
        workingRules[selectedRuleIndex] = current
        config.enabled = binding.rulesEnabled.isChecked
        config.scope = binding.rulesScope.selectedItemPosition
        config.rules = workingRules.toList()
        renderRuleList()
        toast(R.string.forwarding_saved)
    }

    private fun showChannelSelector() {
        val channels = ForwardingChannels.allRuleChannels
        val labels = channels.map(ForwardingChannels::displayName).toTypedArray()
        val checked = channels.map(selectedChannels::contains).toBooleanArray()
        val draft = selectedChannels.toMutableSet()
        AlertDialog.Builder(this)
            .setTitle(R.string.forwarding_rules_channels)
            .setMultiChoiceItems(labels, checked) { _, which, enabled ->
                if (enabled) draft += channels[which] else draft -= channels[which]
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                selectedChannels = draft
                updateChannelSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun testRule() {
        val rule = readEditor() ?: return
        val candidates = if (binding.rulesScope.selectedItemPosition == ForwardingRulesConfig.SCOPE_FORWARDING_ONLY) {
            selectedChannels - ForwardingChannels.SMS_DIRECT
        } else {
            selectedChannels.toSet()
        }
        val decision = ForwardingRuleEngine(listOf(rule)).evaluate(
            sender = "测试发送方",
            body = binding.rulesTestBody.text?.toString().orEmpty(),
            subscriptionId = -1,
            channelCandidates = candidates,
            simSlotIndex = when (rule.simScope) {
                ForwardingRule.SIM_1 -> 0
                ForwardingRule.SIM_2 -> 1
                else -> null
            },
        )
        binding.rulesTestResult.text = if (decision.allowedChannels.isNotEmpty()) {
            binding.rulesTestResult.setTextColor(ContextCompat.getColor(this, R.color.miui_action_blue))
            getString(
                R.string.forwarding_rules_match,
                decision.allowedChannels.map(ForwardingChannels::displayName).joinToString("、"),
            )
        } else {
            binding.rulesTestResult.setTextColor(ContextCompat.getColor(this, R.color.miui_unread_red))
            getString(R.string.forwarding_rules_no_match)
        }
    }

    private fun updateChannelSummary() {
        binding.rulesChannelsSummary.text = selectedChannels
            .map(ForwardingChannels::displayName)
            .joinToString("、")
            .ifBlank { getString(R.string.forwarding_rules_channels_empty) }
    }

    private fun newRule(number: Int) = ForwardingRule(
        name = getString(R.string.forwarding_rules_rule_default, number),
    )

    private fun splitValues(value: String): List<String> = value
        .split('\n', ',', '，', ';', '；', '|')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    private fun isRegexValid(value: String) = value.isBlank() || runCatching { Regex(value) }.isSuccess
}
