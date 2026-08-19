package org.fossify.messages.activities

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityLowBatterySettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.showSmsStyled
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.helpers.LowBatteryCheckWorker

class LowBatterySettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityLowBatterySettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.lowBatteryAppbar),
            padBottomImeAndSystem = listOf(binding.lowBatteryNestedScrollview),
        )
        setupMaterialScrollListener(
            scrollingView = binding.lowBatteryNestedScrollview,
            topAppBar = binding.lowBatteryAppbar,
        )
        setupTopAppBar(binding.lowBatteryAppbar, NavigationIcon.Arrow)
        binding.lowBatteryToolbar.title = ""
        applyMiuiTopAppBarChrome(binding.lowBatteryAppbar, binding.lowBatteryToolbar)

        val config = config

        if (config.enableLowBatteryReminder && config.lowBatteryChannels.isEmpty()) {
            config.enableLowBatteryReminder = false
        }
        binding.lowBatteryEnableSwitch.isChecked = config.enableLowBatteryReminder
        binding.lowBatteryThresholdSlider.value = config.lowBatteryThreshold.toFloat()
        updateThresholdSummary(config.lowBatteryThreshold)
        updateChannelSummary()

        binding.lowBatteryEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && config.lowBatteryChannels.isEmpty()) {
                config.enableLowBatteryReminder = false
                binding.lowBatteryEnableSwitch.isChecked = false
                showChannelSelector(enableAfterSelection = true)
            } else {
                config.enableLowBatteryReminder = isChecked
                if (!isChecked) config.lowBatteryLastNotifiedLevel = -1
                LowBatteryCheckWorker.sync(applicationContext)
            }
        }

        binding.lowBatteryThresholdSlider.addOnChangeListener { _, value, _ ->
            val threshold = value.toInt()
            config.lowBatteryThreshold = threshold
            updateThresholdSummary(threshold)
        }

        binding.lowBatteryChannelsHolder.setOnClickListener { showChannelSelector() }
    }

    private fun updateThresholdSummary(threshold: Int) {
        binding.lowBatteryThresholdSummary.text = getString(R.string.low_battery_threshold_summary, threshold)
    }

    private fun showChannelSelector(enableAfterSelection: Boolean = false) {
        val channels = ForwardingChannels.lowBatteryChannels
        val selected = config.lowBatteryChannels.toMutableSet()
        val labels = channels.map(ForwardingChannels::displayName).toTypedArray()
        val checked = channels.map(selected::contains).toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.low_battery_channels_label)
            .setMultiChoiceItems(labels, checked) { _, which, enabled ->
                if (enabled) selected += channels[which] else selected -= channels[which]
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (enableAfterSelection && selected.isEmpty()) {
                    toast(R.string.low_battery_channels_required)
                    return@setPositiveButton
                }
                config.lowBatteryChannels = selected
                updateChannelSummary()
                if (selected.isEmpty() && config.enableLowBatteryReminder) {
                    binding.lowBatteryEnableSwitch.isChecked = false
                    toast(R.string.low_battery_channels_required)
                } else if (enableAfterSelection) {
                    binding.lowBatteryEnableSwitch.isChecked = true
                } else {
                    LowBatteryCheckWorker.sync(applicationContext)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun updateChannelSummary() {
        val selected = config.lowBatteryChannels
        binding.lowBatteryChannelsSummary.text = ForwardingChannels.lowBatteryChannels
            .filter(selected::contains)
            .map(ForwardingChannels::displayName)
            .joinToString("、")
            .ifBlank { getString(R.string.low_battery_channels_empty) }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.lowBatteryAppbar, binding.lowBatteryToolbar)
    }
}
