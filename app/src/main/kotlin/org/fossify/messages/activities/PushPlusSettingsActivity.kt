package org.fossify.messages.activities

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Telephony
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.toast
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
        loadConfig()

        binding.pushplusSave.setOnClickListener {
            if (saveConfig()) {
                updateDiagnostics()
                toast(R.string.pushplus_saved)
            }
        }

        binding.pushplusTest.setOnClickListener {
            if (saveConfig(requireToken = true)) {
                val sender = binding.pushplusTestSender.value.trim()
                val body = binding.pushplusTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast(R.string.pushplus_test_fields_required)
                } else {
                    PushPlusWorker.enqueueTest(applicationContext, sender, body)
                    toast(R.string.pushplus_test_queued)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = true
        updateLastStatus()
        updateDiagnostics()
    }

    private fun loadConfig() = with(binding) {
        pushplusEnabled.isChecked = forwardingConfig.enabled
        pushplusToken.setText(forwardingConfig.getToken())
        pushplusTitlePrefix.setText(forwardingConfig.titlePrefix)
        pushplusIncludeSender.isChecked = forwardingConfig.includeSender
        pushplusIncludeSim.isChecked = forwardingConfig.includeSim
        pushplusIncludeTime.isChecked = forwardingConfig.includeTime
        pushplusTestSender.setText(getString(R.string.pushplus_test_sender_default))
        pushplusTestBody.setText(getString(R.string.pushplus_test_body_default))
        updateLastStatus()
        updateDiagnostics()
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

    private fun updateDiagnostics() {
        val isDefaultSms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(this) == packageName
        }
        val canReceive = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val hasToken = forwardingConfig.getToken().isNotBlank()
        val receiverStatus = forwardingConfig.lastReceiverStatus.ifBlank {
            getString(R.string.pushplus_receiver_never)
        }
        binding.pushplusDiagnostics.text = getString(
            R.string.pushplus_diagnostics,
            mark(isDefaultSms),
            mark(canReceive),
            mark(forwardingConfig.enabled),
            mark(hasToken),
            receiverStatus
        )
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "✕"
}
