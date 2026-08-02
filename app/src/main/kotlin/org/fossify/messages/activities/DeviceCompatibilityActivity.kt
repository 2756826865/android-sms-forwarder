package org.fossify.messages.activities

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityDeviceCompatibilityBinding

class DeviceCompatibilityActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDeviceCompatibilityBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.compatibilityScrollview))
        setupMaterialScrollListener(binding.compatibilityScrollview, binding.compatibilityAppbar)
        setupTopAppBar(binding.compatibilityAppbar, NavigationIcon.Arrow)
        binding.compatibilityToolbar.title = ""
        binding.compatibilityDefaultSms.setOnClickListener { requestDefaultSmsRole() }
        binding.compatibilityAutostart.setOnClickListener { openAutoStartSettings() }
        binding.compatibilityBattery.setOnClickListener { requestUnrestrictedBattery() }
        binding.compatibilityPermissions.setOnClickListener { openAppDetails() }
    }

    override fun onResume() {
        super.onResume()
        window.statusBarColor = Color.rgb(247, 247, 247)
        window.navigationBarColor = Color.rgb(247, 247, 247)
        binding.compatibilityDevice.text = getString(
            R.string.compatibility_device,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.RELEASE,
        )
        val roleReady = isDefaultSmsApp()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val batteryReady = powerManager.isIgnoringBatteryOptimizations(packageName)
        binding.compatibilityStatus.text = getString(
            R.string.compatibility_status,
            if (roleReady) getString(R.string.compatibility_ready) else getString(R.string.compatibility_not_ready),
            if (batteryReady) getString(R.string.compatibility_ready) else getString(R.string.compatibility_not_ready),
        )
    }

    private fun isDefaultSmsApp(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } else {
        Telephony.Sms.getDefaultSmsPackage(this) == packageName
    }

    private fun requestDefaultSmsRole() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        }
        if (intent != null) launchOrFallback(intent)
    }

    private fun openAutoStartSettings() {
        val candidates = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> listOf(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                ),
            )

            "huawei", "honor" -> listOf(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
                ComponentName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            )

            else -> emptyList()
        }
        val launched = candidates.any { component ->
            runCatching {
                startActivity(Intent().apply { this.component = component })
            }.isSuccess
        }
        if (!launched) openAppDetails()
    }

    private fun requestUnrestrictedBattery() {
        val directRequest = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(directRequest) }.onFailure {
            launchOrFallback(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun launchOrFallback(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { openAppDetails() }
    }

    private fun openAppDetails() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }
}
