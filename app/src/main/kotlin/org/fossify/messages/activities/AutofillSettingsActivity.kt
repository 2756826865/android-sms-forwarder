package org.fossify.messages.activities

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import org.fossify.messages.autofill.AutofillConfig
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

    private fun setupToolbar() {
        binding.autofillToolbar.setNavigationIcon(org.fossify.commons.R.drawable.ic_arrow_left_vector)
        binding.autofillToolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupViews() {
        binding.autofillSwitchEnabled.isChecked = config.enabled
        binding.autofillSwitchEnabled.setOnCheckedChangeListener { _, isChecked ->
            config.enabled = isChecked
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
    }
}
