package org.fossify.messages.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivitySettingsBinding
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
        bindActions()
    }

    override fun onResume() {
        super.onResume()
        window.statusBarColor = Color.rgb(247, 247, 247)
        window.navigationBarColor = Color.rgb(247, 247, 247)
        binding.settingsShowAvatars.isChecked = config.showListAvatars
        binding.settingsTextAvatars.isChecked = config.showLetterAvatars
        binding.settingsTextAvatarsHolder.isEnabled = config.showListAvatars
        binding.settingsTextAvatarsHolder.alpha = if (config.showListAvatars) 1f else 0.45f
        updateDelayLabel()
    }

    private fun bindActions() = binding.apply {
        settingsShowAvatarsHolder.setOnClickListener {
            settingsShowAvatars.toggle()
            config.showListAvatars = settingsShowAvatars.isChecked
            settingsTextAvatarsHolder.isEnabled = settingsShowAvatars.isChecked
            settingsTextAvatarsHolder.alpha = if (settingsShowAvatars.isChecked) 1f else 0.45f
            refreshConversations()
        }
        settingsTextAvatarsHolder.setOnClickListener {
            if (!config.showListAvatars) return@setOnClickListener
            settingsTextAvatars.toggle()
            config.showLetterAvatars = settingsTextAvatars.isChecked
            refreshConversations()
        }
        settingsPushplusHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, PushPlusSettingsActivity::class.java))
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
            config.fullHistorySyncedV2 = false
            refreshConversations()
            toast(R.string.resync_started)
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
            .show()
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
