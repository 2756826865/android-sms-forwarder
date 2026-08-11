package org.fossify.messages.activities

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityRemoteSmsCommandSettingsBinding
import org.fossify.messages.extensions.applyMiuiPageChrome
import org.fossify.messages.extensions.showSmsStyled
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.remote.RemoteControlReceiptConfig
import org.fossify.messages.remote.RemoteSmsCommandConfig

class RemoteSmsCommandSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityRemoteSmsCommandSettingsBinding::inflate)
    private val config by lazy { RemoteSmsCommandConfig(applicationContext) }
    private val receiptConfig by lazy { RemoteControlReceiptConfig(applicationContext) }
    private var selectedReceiptChannels = emptySet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.remoteSmsScrollview))
        setupMaterialScrollListener(binding.remoteSmsScrollview, binding.remoteSmsAppbar)
        setupTopAppBar(binding.remoteSmsAppbar, NavigationIcon.Arrow)
        loadConfig()
        binding.remoteSmsReceiptChannels.setOnClickListener { showReceiptChannelSelector() }
        binding.remoteSmsSave.setOnClickListener {
            config.enabled = binding.remoteSmsEnabled.isChecked
            config.authorizedNumbers = binding.remoteSmsAuthorized.value
            receiptConfig.enabled = binding.remoteSmsReceiptEnabled.isChecked
            receiptConfig.includeDelivered = binding.remoteSmsReceiptDelivered.isChecked
            receiptConfig.channels = selectedReceiptChannels
            toast(R.string.forwarding_saved)
            loadConfig()
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiPageChrome()
        loadConfig()
    }

    private fun loadConfig() = with(binding) {
        remoteSmsEnabled.isChecked = config.enabled
        remoteSmsAuthorized.setText(config.authorizedNumbers)
        remoteSmsReceiptEnabled.isChecked = receiptConfig.enabled
        remoteSmsReceiptDelivered.isChecked = receiptConfig.includeDelivered
        selectedReceiptChannels = receiptConfig.channels
        updateReceiptChannelSummary()
        remoteSmsStatus.text = config.lastStatus.ifBlank { getString(R.string.remote_sms_status_empty) }
        remoteSmsLogs.text = config.logs().ifBlank { getString(R.string.remote_sms_logs_empty) }
    }

    private fun showReceiptChannelSelector() {
        val channels = ForwardingChannels.allRuleChannels
        val labels = channels.map(ForwardingChannels::displayName).toTypedArray()
        val checked = channels.map(selectedReceiptChannels::contains).toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_sms_receipt_channels)
            .setMultiChoiceItems(labels, checked) { _, which, enabled ->
                selectedReceiptChannels = if (enabled) {
                    selectedReceiptChannels + channels[which]
                } else {
                    selectedReceiptChannels - channels[which]
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> updateReceiptChannelSummary() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun updateReceiptChannelSummary() {
        binding.remoteSmsReceiptChannelsSummary.text = selectedReceiptChannels
            .map(ForwardingChannels::displayName)
            .joinToString("、")
            .ifBlank { getString(R.string.remote_sms_receipt_channels_empty) }
    }
}
