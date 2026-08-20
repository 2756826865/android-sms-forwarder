package org.fossify.messages.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivitySettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.showSmsStyled
import org.fossify.messages.helpers.HOME_LIST_DENSITY_10
import org.fossify.messages.helpers.HOME_LIST_DENSITY_4
import org.fossify.messages.helpers.HOME_LIST_DENSITY_6
import org.fossify.messages.helpers.HOME_LIST_DENSITY_8
import org.fossify.messages.helpers.liveisland.LiveIslandCoordinator
import org.fossify.messages.helpers.refreshConversations

class SettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.settingsAppbar),
            padBottomImeAndSystem = listOf(binding.settingsNestedScrollview)
        )
        setupMaterialScrollListener(
            scrollingView = binding.settingsNestedScrollview,
            topAppBar = binding.settingsAppbar
        )
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)
        binding.settingsToolbar.title = getString(R.string.settings_messages_title)
        applyMiuiTopAppBarChrome(binding.settingsAppbar, binding.settingsToolbar)
        bindActions()
        bindToggles()
        bindHomeListDensity()
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.settingsAppbar, binding.settingsToolbar)
        refreshToggleStates()
        updateHomeListDensitySummary()
    }

    private fun bindToggles() {
        binding.settingsHomeBottomNavSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.showHomeBottomNavigation = isChecked
        }
        binding.settingsLiveIslandSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.enableLiveIsland = isChecked
            updateLiveIslandSummary()
        }
        refreshToggleStates()
    }

    private fun refreshToggleStates() {
        binding.settingsHomeBottomNavSwitch.isChecked = config.showHomeBottomNavigation
        binding.settingsLiveIslandSwitch.isChecked = config.enableLiveIsland
        updateLiveIslandSummary()
    }

    private fun updateLiveIslandSummary() {
        binding.settingsLiveIslandSummary.text = buildString {
            append(LiveIslandCoordinator.getStatusLabel(this@SettingsActivity))
            append('\n')
            append(getString(R.string.settings_live_island_summary))
        }
    }

    private fun bindHomeListDensity() {
        binding.settingsHomeListDensityHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(HOME_LIST_DENSITY_4, getString(R.string.settings_home_list_density_4)),
                RadioItem(HOME_LIST_DENSITY_6, getString(R.string.settings_home_list_density_6)),
                RadioItem(HOME_LIST_DENSITY_8, getString(R.string.settings_home_list_density_8)),
                RadioItem(HOME_LIST_DENSITY_10, getString(R.string.settings_home_list_density_10)),
            )
            RadioGroupDialog(this, items, config.homeListDensity) {
                config.homeListDensity = it as Int
                updateHomeListDensitySummary()
            }
        }
        updateHomeListDensitySummary()
    }

    private fun updateHomeListDensitySummary() {
        binding.settingsHomeListDensitySummary.text =
            getString(R.string.settings_home_list_density_summary, config.homeListDensity)
    }

    private fun bindActions() = binding.apply {
        settingsForwardingHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, ForwardingChannelsActivity::class.java))
        }
        settingsScheduledHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, ScheduledMessagesActivity::class.java))
        }
        settingsBulkSendHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, BulkSendActivity::class.java))
        }
        settingsLowBatteryHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, LowBatterySettingsActivity::class.java))
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
        settingsCompatibilityHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, DeviceCompatibilityActivity::class.java))
        }
        settingsBlockingHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, BlockingSettingsActivity::class.java))
        }
        settingsRecentlyDeletedHolder.setOnClickListener {
            config.useRecycleBin = true
            startActivity(Intent(this@SettingsActivity, RecycleBinConversationsActivity::class.java))
        }
        settingsAboutHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, AboutActivity::class.java))
        }
    }
}
