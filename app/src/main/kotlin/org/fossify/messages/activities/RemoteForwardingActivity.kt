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
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.remoteForwardingAppbar, binding.remoteForwardingToolbar)
        updateSummaries()
    }

    private fun updateSummaries() = binding.apply {
        remoteForwardingSmsSummary.text = remoteSmsConfig.summary()
        remoteForwardingDingtalkSummary.text = dingTalkRemoteSummary()
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
}
