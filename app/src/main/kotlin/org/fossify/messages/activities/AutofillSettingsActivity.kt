package org.fossify.messages.activities

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import org.fossify.messages.R
import org.fossify.messages.autofill.AutofillConfig
import org.fossify.messages.autofill.SmsAutofillAccessibilityService
import org.fossify.messages.databinding.ActivityAutofillSettingsBinding

class AutofillSettingsActivity : SimpleActivity() {

    private lateinit var binding: ActivityAutofillSettingsBinding
    private lateinit var config: AutofillConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutofillSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = AutofillConfig(this)
        setupToolbar()
        setupViews()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun setupToolbar() {
        binding.autofillToolbar.setNavigationIcon(org.fossify.commons.R.drawable.ic_arrow_left_vector)
        binding.autofillToolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupViews() {
        binding.autofillSwitchEnabled.isChecked = config.enabled
        binding.autofillSwitchEnabled.setOnCheckedChangeListener { _, isChecked ->
            config.enabled = isChecked
            if (isChecked && !SmsAutofillAccessibilityService.isServiceRunning()) {
                openAccessibilitySettings()
            }
        }

        binding.autofillSwitchAutoSubmit.isChecked = config.autoSubmit
        binding.autofillSwitchAutoSubmit.setOnCheckedChangeListener { _, isChecked ->
            config.autoSubmit = isChecked
        }

        binding.autofillSwitchCopyClipboard.isChecked = config.copyToClipboard
        binding.autofillSwitchCopyClipboard.setOnCheckedChangeListener { _, isChecked ->
            config.copyToClipboard = isChecked
        }

        binding.autofillSwitchFloatingPill.isChecked = config.enableFloatingPill
        binding.autofillSwitchFloatingPill.setOnCheckedChangeListener { _, isChecked ->
            config.enableFloatingPill = isChecked
            if (isChecked && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    android.widget.Toast.makeText(this, "请授予「悬浮窗 / 显示在其他应用上层」权限以启用胶囊", android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        binding.autofillServiceStatus.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private fun updateServiceStatus() {
        val isRunning = SmsAutofillAccessibilityService.isServiceRunning()
        if (isRunning) {
            binding.autofillServiceStatus.text = "● 无障碍辅助服务：运行中（已就绪）"
            binding.autofillServiceStatus.setTextColor(getColor(R.color.brand_green))
        } else {
            binding.autofillServiceStatus.text = "● 无障碍辅助服务：未开启（点击去开启）"
            binding.autofillServiceStatus.setTextColor(getColor(R.color.miui_warning_text))
        }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
