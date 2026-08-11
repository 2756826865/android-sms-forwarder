package org.fossify.messages.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivitySettingsBinding
import org.fossify.messages.extensions.applyMiuiPageChrome
import org.fossify.messages.extensions.showSmsStyled
import org.fossify.messages.extensions.config
import org.fossify.messages.helpers.refreshConversations

class SettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.settingsNestedScrollview))
        setupMaterialScrollListener(
            scrollingView = binding.settingsNestedScrollview,
            topAppBar = binding.settingsAppbar
        )
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)
        binding.settingsToolbar.title = ""
        config.useRecycleBin = true
        bindActions()
        binding.settingsHomeBottomNavSwitch.isChecked = config.showHomeBottomNavigation
        binding.settingsHomeBottomNavSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.showHomeBottomNavigation = isChecked
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiPageChrome()
        binding.settingsHomeBottomNavSwitch.isChecked = config.showHomeBottomNavigation
    }

    private fun bindActions() = binding.apply {
        settingsBulkSendHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, BulkSendActivity::class.java))
        }
        settingsScheduledHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, ScheduledMessagesActivity::class.java))
        }
        settingsBlockingHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, BlockingSettingsActivity::class.java))
        }
        settingsRecentlyDeletedHolder.setOnClickListener {
            config.useRecycleBin = true
            startActivity(Intent(this@SettingsActivity, RecycleBinConversationsActivity::class.java))
        }
        settingsSyncHolder.setOnClickListener {
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.settings_sync)
                .setMessage(R.string.settings_sync_confirm)
                .setPositiveButton(R.string.resync_all_messages) { _, _ ->
                    config.fullHistorySyncedV2 = false
                    refreshConversations()
                    toast(R.string.resync_started)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .showSmsStyled()
        }
        settingsForwardingHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, ForwardingChannelsActivity::class.java))
        }
        settingsCompatibilityHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, DeviceCompatibilityActivity::class.java))
        }
        settingsAboutHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, AboutActivity::class.java))
        }
    }
}
