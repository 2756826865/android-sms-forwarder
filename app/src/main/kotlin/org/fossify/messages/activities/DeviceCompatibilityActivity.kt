package org.fossify.messages.activities

import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.view.View
import android.widget.Toast
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
        binding.compatibilityRefresh.setOnClickListener { refreshCompatibilityStatus() }
        binding.compatibilityCopyFix.setOnClickListener { copyAdbRepairCommands() }
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
        refreshCompatibilityStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_SMS_ROLE -> binding.root.postDelayed({
                refreshCompatibilityStatus()
                if (isDefaultSmsApp() && !isSmsChainReady()) {
                    requestLegacyDefaultSmsChange()
                }
            }, ROLE_STATE_SETTLE_DELAY_MS)

            REQUEST_LEGACY_DEFAULT_SMS -> binding.root.postDelayed(
                { refreshCompatibilityStatus() },
                ROLE_STATE_SETTLE_DELAY_MS,
            )
        }
    }

    private fun refreshCompatibilityStatus() {
        val roleReady = isDefaultSmsApp()
        val routedPackage = getLegacySmsRoute()
        val routeReady = routedPackage == packageName
        val writeSmsReady = isWriteSmsAllowed()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val batteryReady = powerManager.isIgnoringBatteryOptimizations(packageName)
        val smsChainReady = roleReady && routeReady && writeSmsReady
        binding.compatibilityDefaultSms.text = getString(
            if (roleReady && !smsChainReady) {
                R.string.compatibility_try_legacy_sms_switch
            } else {
                R.string.compatibility_default_sms
            }
        )
        binding.compatibilityStatus.text = getString(
            R.string.compatibility_status,
            if (roleReady) getString(R.string.compatibility_ready) else getString(R.string.compatibility_not_ready),
            routedPackage ?: getString(R.string.compatibility_unknown),
            if (routeReady) getString(R.string.compatibility_ready) else getString(R.string.compatibility_not_ready),
            if (writeSmsReady) getString(R.string.compatibility_allowed) else getString(R.string.compatibility_not_allowed),
            if (batteryReady) getString(R.string.compatibility_ready) else getString(R.string.compatibility_not_ready),
            if (smsChainReady) getString(R.string.compatibility_chain_ready) else getString(R.string.compatibility_chain_split),
        )
        binding.compatibilityCopyFix.visibility = if (smsChainReady) View.GONE else View.VISIBLE
    }

    private fun isDefaultSmsApp(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } else {
        Telephony.Sms.getDefaultSmsPackage(this) == packageName
    }

    private fun getLegacySmsRoute(): String? = runCatching {
        Settings.Secure.getString(contentResolver, SMS_DEFAULT_APPLICATION_KEY)
    }.getOrNull()

    private fun isWriteSmsAllowed(): Boolean = runCatching {
        val appOps = getSystemService(AppOpsManager::class.java)
        val mode = appOps?.checkOpNoThrow(WRITE_SMS_APP_OP, Process.myUid(), packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun isSmsChainReady(): Boolean =
        isDefaultSmsApp() && getLegacySmsRoute() == packageName && isWriteSmsAllowed()

    private fun copyAdbRepairCommands() {
        val commands = """
            adb shell settings --user 0 put secure sms_default_application $packageName
            adb shell appops set $packageName WRITE_SMS allow
        """.trimIndent()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.compatibility_adb_commands), commands))
        Toast.makeText(this, R.string.compatibility_commands_copied, Toast.LENGTH_SHORT).show()
    }

    private fun requestDefaultSmsRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isDefaultSmsApp()) {
                if (!isSmsChainReady()) requestLegacyDefaultSmsChange()
                return
            }

            val intent = getSystemService(RoleManager::class.java)
                ?.createRequestRoleIntent(RoleManager.ROLE_SMS)
            if (intent != null) {
                runCatching { startActivityForResult(intent, REQUEST_SMS_ROLE) }
                    .onFailure { openAppDetails() }
            }
            return
        }

        requestLegacyDefaultSmsChange()
    }

    @Suppress("DEPRECATION")
    private fun requestLegacyDefaultSmsChange() {
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        runCatching { startActivityForResult(intent, REQUEST_LEGACY_DEFAULT_SMS) }
            .onFailure {
                Toast.makeText(this, R.string.compatibility_legacy_sms_switch_unavailable, Toast.LENGTH_LONG).show()
            }
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

    private companion object {
        const val SMS_DEFAULT_APPLICATION_KEY = "sms_default_application"
        const val WRITE_SMS_APP_OP = "android:write_sms"
        const val REQUEST_SMS_ROLE = 801
        const val REQUEST_LEGACY_DEFAULT_SMS = 802
        const val ROLE_STATE_SETTLE_DELAY_MS = 500L
    }
}
