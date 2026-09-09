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
import org.fossify.messages.remote.repository.RemoteSourceRepository

class RemoteForwardingActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityRemoteForwardingBinding::inflate)
    private val multiConfig by lazy { MultiForwardConfig(applicationContext) }
    private val remoteSmsConfig by lazy { RemoteSmsCommandConfig(applicationContext) }
    private val remoteRepository by lazy { RemoteSourceRepository.getInstance(applicationContext) }

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
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.remoteForwardingAppbar, binding.remoteForwardingToolbar)
        remoteRepository.syncLegacySourcesFromClassic()
        updateSummaries()
    }

    private fun updateSummaries() = binding.apply {
        remoteForwardingSmsSummary.text = remoteSmsConfig.summary()
        remoteForwardingDingtalkSummary.text = summary(
            multiConfig.dingTalkRemoteClientId().isNotBlank(),
            multiConfig.dingTalkRemoteControlEnabled
        )
        remoteForwardingFeishuSummary.text = summary(
            multiConfig.feishuRemoteAppId().isNotBlank(),
            multiConfig.feishuRemoteControlEnabled
        )
        remoteForwardingWecomSummary.text = summary(
            multiConfig.weComRemoteBotId().isNotBlank(),
            multiConfig.weComRemoteControlEnabled
        )
        remoteForwardingEmailSummary.text = summary(
            multiConfig.emailRemoteHost().isNotBlank() && multiConfig.emailRemoteUser().isNotBlank(),
            multiConfig.emailRemoteControlEnabled
        )
        remoteForwardingTelegramSummary.text = summary(
            multiConfig.telegramRemoteBotToken().isNotBlank(),
            multiConfig.telegramRemoteControlEnabled
        )
        remoteForwardingWebsocketSummary.text = summary(
            multiConfig.websocketRemoteUrl().isNotBlank(),
            multiConfig.websocketRemoteControlEnabled
        )
    }

    private fun summary(configured: Boolean, enabled: Boolean): String = when {
        configured && enabled -> getString(R.string.forwarding_configured_enabled)
        configured -> getString(R.string.forwarding_configured_disabled)
        else -> getString(R.string.forwarding_not_configured)
    }
}
