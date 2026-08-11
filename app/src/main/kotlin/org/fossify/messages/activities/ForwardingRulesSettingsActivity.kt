package org.fossify.messages.activities

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityForwardingRulesSettingsBinding
import org.fossify.messages.extensions.applyMiuiPageChrome
import org.fossify.messages.extensions.bindMiuiOptions
import org.fossify.messages.extensions.showSmsStyled
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingRule
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig

class ForwardingRulesSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityForwardingRulesSettingsBinding::inflate)
    private val config by lazy { ForwardingRulesConfig(applicationContext) }
    private var selectedChannels = ForwardingChannels.allRuleChannels.toMutableSet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.rulesScrollview))
        setupMaterialScrollListener(binding.rulesScrollview, binding.rulesAppbar)
        setupTopAppBar(binding.rulesAppbar, NavigationIcon.Arrow)
        binding.rulesScope.bindMiuiOptions(R.array.forwarding_rules_scope_options)
        binding.rulesSimScope.bindMiuiOptions(R.array.forwarding_rules_sim_options)
        loadRule()
        binding.rulesChannels.setOnClickListener { showChannelSelector() }
        binding.rulesTest.setOnClickListener { testRule() }
        binding.rulesSave.setOnClickListener {
            val rule = readRule() ?: return@setOnClickListener
            config.enabled = binding.rulesEnabled.isChecked
            config.scope = binding.rulesScope.selectedItemPosition
            config.rules = listOf(rule)
            toast(R.string.forwarding_saved)
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiPageChrome()
    }

    private fun loadRule() {
        val rule = config.rules.firstOrNull() ?: ForwardingRule(name = "默认规则")
        binding.rulesEnabled.isChecked = config.enabled
        binding.rulesScope.setSelection(config.scope.coerceIn(0, 2))
        binding.rulesSimScope.setSelection(
            when (rule.simScope) {
                ForwardingRule.SIM_1 -> 1
                ForwardingRule.SIM_2 -> 2
                else -> 0
            }
        )
        binding.rulesAllowKeywords.setText(rule.includeKeywords.joinToString("\n"))
        binding.rulesExcludeKeywords.setText(rule.excludeKeywords.joinToString("\n"))
        binding.rulesAllowRegex.setText(rule.includeRegex)
        binding.rulesExcludeRegex.setText(rule.excludeRegex)
        binding.rulesTestBody.setText(getString(R.string.forwarding_rules_test_body_default))
        selectedChannels = rule.channels.toMutableSet()
        updateChannelSummary()
    }

    private fun readRule(): ForwardingRule? {
        val includeRegex = binding.rulesAllowRegex.text?.toString().orEmpty().trim()
        val excludeRegex = binding.rulesExcludeRegex.text?.toString().orEmpty().trim()
        if (!isRegexValid(includeRegex) || !isRegexValid(excludeRegex)) {
            toast(R.string.forwarding_rules_invalid_regex)
            return null
        }
        return ForwardingRule(
            name = "默认规则",
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

    private fun showChannelSelector() {
        val channels = ForwardingChannels.allRuleChannels
        val labels = channels.map(ForwardingChannels::displayName).toTypedArray()
        val checked = channels.map(selectedChannels::contains).toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.forwarding_rules_channels)
            .setMultiChoiceItems(labels, checked) { _, which, enabled ->
                if (enabled) selectedChannels += channels[which] else selectedChannels -= channels[which]
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> updateChannelSummary() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun testRule() {
        val rule = readRule() ?: return
        val candidates = config.channelCandidatesForScope(selectedChannels.toSet())
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
            getString(
                R.string.forwarding_rules_match,
                decision.allowedChannels.map(ForwardingChannels::displayName).joinToString("、"),
            )
        } else {
            getString(R.string.forwarding_rules_no_match)
        }
    }

    private fun updateChannelSummary() {
        binding.rulesChannelsSummary.text = selectedChannels
            .map(ForwardingChannels::displayName)
            .joinToString("、")
            .ifBlank { getString(R.string.forwarding_rules_channels_empty) }
    }

    private fun splitValues(value: String): List<String> = value
        .split('\n', ',', '，', ';', '；', '|')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    private fun isRegexValid(value: String) = value.isBlank() || runCatching { Regex(value) }.isSuccess
}
