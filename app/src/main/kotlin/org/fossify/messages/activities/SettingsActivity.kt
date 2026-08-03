package org.fossify.messages.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowInsetsControllerCompat
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivitySettingsBinding
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
    }

    override fun onResume() {
        super.onResume()
        window.statusBarColor = Color.rgb(247, 247, 247)
        window.navigationBarColor = Color.rgb(247, 247, 247)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = true
        updateDelayLabel()
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
        settingsBatchDelayHolder.setOnClickListener { showDelayDialog() }
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

    private fun showDelayDialog() {
        val labels = (0..5).map { delayLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_batch_delay)
            .setSingleChoiceItems(labels, config.bulkSendDelaySeconds) { dialog, which ->
                config.bulkSendDelaySeconds = which
                updateDelayLabel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun updateDelayLabel() {
        binding.settingsBatchDelay.text = delayLabel(config.bulkSendDelaySeconds)
    }

    private fun delayLabel(seconds: Int): String = if (seconds == 0) {
        getString(R.string.batch_delay_now)
    } else {
        getString(R.string.batch_delay_seconds, seconds)
    }
}
