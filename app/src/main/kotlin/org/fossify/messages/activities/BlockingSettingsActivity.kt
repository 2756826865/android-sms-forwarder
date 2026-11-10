package org.fossify.messages.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityBlockingSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.config

class BlockingSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityBlockingSettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.blockingAppbar),
            padBottomImeAndSystem = listOf(binding.blockingScrollview),
        )
        setupMaterialScrollListener(binding.blockingScrollview, binding.blockingAppbar)
        setupTopAppBar(binding.blockingAppbar, NavigationIcon.Arrow)
        binding.blockingToolbar.title = ""
        applyMiuiTopAppBarChrome(binding.blockingAppbar, binding.blockingToolbar)

        binding.blockingCalls.setOnClickListener {
            runCatching { startActivity(Intent("android.settings.BLOCKED_NUMBER_SETTINGS")) }
                .onFailure { openBlacklist() }
        }
        binding.blockingSms.setOnClickListener {
            startActivity(Intent(this, ManageBlockedKeywordsActivity::class.java))
        }
        binding.blockingBlacklist.setOnClickListener { openBlacklist() }
        binding.blockingWhitelist.setOnClickListener {
            startActivity(Intent(this, ManageWhitelistActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.blockingAppbar, binding.blockingToolbar)
        binding.blockingBlacklistCount.text =
            getString(R.string.number_count, config.blacklistedNumbers.size)
        binding.blockingWhitelistCount.text = getString(R.string.number_count, config.whitelistedNumbers.size)
    }

    private fun openBlacklist() {
        startActivity(Intent(this, ManageBlacklistActivity::class.java))
    }
}
