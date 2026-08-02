package org.fossify.messages.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityBlockingSettingsBinding
import org.fossify.messages.extensions.config

class BlockingSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityBlockingSettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.blockingScrollview))
        setupMaterialScrollListener(binding.blockingScrollview, binding.blockingAppbar)
        setupTopAppBar(binding.blockingAppbar, NavigationIcon.Arrow)
        binding.blockingToolbar.title = ""
        window.statusBarColor = Color.rgb(247, 247, 247)
        window.navigationBarColor = Color.rgb(247, 247, 247)

        binding.blockingSimOne.setOnClickListener { selectSim(first = true) }
        binding.blockingSimTwo.setOnClickListener { selectSim(first = false) }
        binding.blockingCalls.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_BLOCKED_NUMBER_SETTINGS)) }
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
        binding.blockingBlacklistCount.text =
            getString(R.string.number_count, config.blacklistedNumbers.size)
        binding.blockingWhitelistCount.text = getString(R.string.number_count, config.whitelistedNumbers.size)
    }

    private fun openBlacklist() {
        startActivity(Intent(this, ManageBlacklistActivity::class.java))
    }

    private fun selectSim(first: Boolean) {
        val selectedBackground = getDrawable(R.drawable.miui_segment_selected_background)
        val normalBackground = getDrawable(R.drawable.miui_segment_background)
        binding.blockingSimOne.background = if (first) selectedBackground else normalBackground
        binding.blockingSimTwo.background = if (first) normalBackground else selectedBackground
        binding.blockingSimOne.setTextColor(if (first) Color.BLACK else Color.rgb(119, 119, 119))
        binding.blockingSimTwo.setTextColor(if (first) Color.rgb(119, 119, 119) else Color.BLACK)
    }
}
