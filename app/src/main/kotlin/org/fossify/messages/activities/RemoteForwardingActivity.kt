package org.fossify.messages.activities

import android.content.Intent
import android.os.Bundle
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityRemoteForwardingBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.remote.RemoteSmsCommandConfig

class RemoteForwardingActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityRemoteForwardingBinding::inflate)
    private val multiConfig by lazy { MultiForwardConfig(applicationContext) }
    private val remoteSmsConfig by lazy { RemoteSmsCommandConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.remoteForwardingAppbar),
            padBottomImeAndSystem = listOf(binding.remoteForwardingScrollview),
        )
        setupMaterialScrollListener(binding.remoteForwardingScrollview, binding.remoteForwardingAppbar)
        setupTopAppBar(binding.remoteForwardingAppbar, NavigationIcon.Arrow)
        binding.remoteForwardingToolbar.title = ""
        applyMiuiTopAppBarChrome(binding.remoteForwardingAppbar, binding.remoteForwardingToolbar)

        binding.remoteForwardingSmsHolder.setOnClickListener {
            startActivity(Intent(this, RemoteSmsCommandSettingsActivity::class.java))
        }
        binding.remoteForwardingDingtalkHolder.setOnClickListener {
            startActivity(Intent(this, DingTalkRemoteControlSettingsActivity::class.java))
        }
        binding.remoteForwardingFeishuHolder.setOnClickListener {
            startActivity(Intent(this, FeishuRemoteControlSettingsActivity::class.java))
        }
        binding.remoteForwardingWecomHolder.setOnClickListener {
            startActivity(Intent(this, WeComRemoteControlSettingsActivity::class.java))
        }
        binding.remoteForwardingEmailHolder.setOnClickListener {
            startActivity(Intent(this, EmailRemoteControlSettingsActivity::class.java))
        }
        binding.remoteForwardingTelegramHolder.setOnClickListener {
            startActivity(Intent(this, TelegramRemoteControlSettingsActivity::class.java))
        }
        binding.remoteForwardingWebsocketHolder.setOnClickListener {
            startActivity(Intent(this, WebSocketRemoteControlSettingsActivity::class.java))
        }
        binding.remoteForwardingQqHolder.setOnClickListener {
            startActivity(Intent(this, QqRemoteControlSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.remoteForwardingAppbar, binding.remoteForwardingToolbar)
        updateSummaries()
    }

    private fun updateSummaries() = binding.apply {
        remoteForwardingSmsSummary.text = remoteSmsConfig.summary()
        remoteForwardingDingtalkSummary.text = dingTalkRemoteSummary()
        remoteForwardingFeishuSummary.text = feishuRemoteSummary()
        remoteForwardingWecomSummary.text = weComRemoteSummary()
        remoteForwardingEmailSummary.text = emailRemoteSummary()
        remoteForwardingTelegramSummary.text = telegramRemoteSummary()
        remoteForwardingWebsocketSummary.text = websocketRemoteSummary()
        remoteForwardingQqSummary.text = qqRemoteSummary()
    }

    private fun dingTalkRemoteSummary(): String {
        val configured = multiConfig.dingTalkRemoteClientId().isNotBlank()
        return when {
            multiConfig.dingTalkRemoteControlEnabled && configured ->
                getString(R.string.forwarding_configured_enabled)
            configured -> getString(R.string.forwarding_configured_disabled)
            else -> getString(R.string.forwarding_not_configured)
        }
    }

    private fun feishuRemoteSummary(): String {
        val configured = multiConfig.feishuRemoteAppId().isNotBlank()
        return when {
            multiConfig.feishuRemoteControlEnabled && configured ->
                getString(R.string.forwarding_configured_enabled)
            configured -> getString(R.string.forwarding_configured_disabled)
            else -> getString(R.string.forwarding_not_configured)
        }
    }

    private fun weComRemoteSummary(): String {
        val configured = multiConfig.weComRemoteCorpId().isNotBlank()
        return when {
            multiConfig.weComRemoteControlEnabled && configured ->
                getString(R.string.forwarding_configured_enabled)
            configured -> getString(R.string.forwarding_configured_disabled)
            else -> getString(R.string.forwarding_not_configured)
        }
    }

    private fun emailRemoteSummary(): String {
        val configured = multiConfig.emailRemoteHost().isNotBlank() && multiConfig.emailRemoteUser().isNotBlank()
        return when {
            multiConfig.emailRemoteControlEnabled && configured ->
                getString(R.string.forwarding_configured_enabled)
            configured -> getString(R.string.forwarding_configured_disabled)
            else -> getString(R.string.forwarding_not_configured)
        }
    }

    private fun telegramRemoteSummary(): String {
        val configured = multiConfig.telegramRemoteBotToken().isNotBlank()
        return when {
            multiConfig.telegramRemoteControlEnabled && configured ->
                getString(R.string.forwarding_configured_enabled)
            configured -> getString(R.string.forwarding_configured_disabled)
            else -> getString(R.string.forwarding_not_configured)
        }
    }

    private fun websocketRemoteSummary(): String {
        val configured = multiConfig.websocketRemoteUrl().isNotBlank()
        return when {
            multiConfig.websocketRemoteControlEnabled && configured ->
                getString(R.string.forwarding_configured_enabled)
            configured -> getString(R.string.forwarding_configured_disabled)
            else -> getString(R.string.forwarding_not_configured)
        }
    }

    private fun qqRemoteSummary(): String {
        val configured = multiConfig.qqRemoteWsUrl().isNotBlank()
        return when {
            multiConfig.qqRemoteControlEnabled && configured ->
                getString(R.string.forwarding_configured_enabled)
            configured -> getString(R.string.forwarding_configured_disabled)
            else -> getString(R.string.forwarding_not_configured)
        }
    }
}
