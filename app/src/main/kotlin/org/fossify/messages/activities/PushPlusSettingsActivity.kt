package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityPushplusSettingsBinding
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.forwarding.PushPlusWorker

class PushPlusSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPushplusSettingsBinding::inflate)
    private val forwardingConfig by lazy { PushPlusConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.pushplusScrollview))
        setupMaterialScrollListener(
            scrollingView = binding.pushplusScrollview,
            topAppBar = binding.pushplusAppbar
        )
        setupTopAppBar(binding.pushplusAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.pushplusScrollview)
        loadConfig()

        binding.pushplusSave.setOnClickListener {
            if (saveConfig()) toast(R.string.pushplus_saved)
        }

        binding.pushplusTest.setOnClickListener {
            if (saveConfig(requireToken = true)) {
                PushPlusWorker.enqueueTest(applicationContext)
                toast(R.string.pushplus_test_queued)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateLastStatus()
    }

    private fun loadConfig() = with(binding) {
        pushplusEnabled.isChecked = forwardingConfig.enabled
        pushplusToken.setText(forwardingConfig.getToken())
        pushplusTitlePrefix.setText(forwardingConfig.titlePrefix)
        pushplusIncludeSender.isChecked = forwardingConfig.includeSender
        pushplusIncludeSim.isChecked = forwardingConfig.includeSim
        pushplusIncludeTime.isChecked = forwardingConfig.includeTime
        updateLastStatus()
    }

    private fun saveConfig(requireToken: Boolean = false): Boolean {
        val token = binding.pushplusToken.text?.toString().orEmpty().trim()
        if ((requireToken || binding.pushplusEnabled.isChecked) && token.isBlank()) {
            toast(R.string.pushplus_token_required)
            return false
        }

        forwardingConfig.enabled = binding.pushplusEnabled.isChecked
        forwardingConfig.saveToken(token)
        forwardingConfig.titlePrefix = binding.pushplusTitlePrefix.text?.toString().orEmpty()
        forwardingConfig.includeSender = binding.pushplusIncludeSender.isChecked
        forwardingConfig.includeSim = binding.pushplusIncludeSim.isChecked
        forwardingConfig.includeTime = binding.pushplusIncludeTime.isChecked
        return true
    }

    private fun updateLastStatus() {
        val status = forwardingConfig.lastStatus.ifBlank { getString(R.string.pushplus_status_never) }
        binding.pushplusLastStatus.text = getString(R.string.pushplus_last_status, status)
    }
}
