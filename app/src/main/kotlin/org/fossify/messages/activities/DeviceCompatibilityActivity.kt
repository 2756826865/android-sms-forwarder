package org.fossify.messages.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.BuildConfig
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityDeviceCompatibilityBinding
import org.fossify.messages.extensions.applyMiuiPageChrome
import org.fossify.messages.extensions.config
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingHistoryStore
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.helpers.NOTIFICATION_CHANNEL_ID
import org.fossify.messages.helpers.LowBatteryCheckWorker
import org.fossify.messages.remote.RemoteSmsCommandConfig

class DeviceCompatibilityActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDeviceCompatibilityBinding::inflate)
    private var advancedExpanded = false
    private var lowBatteryWorkState = "未调度"

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshCompatibilityStatus()
        if (granted) sendTestNotificationInternal()
        else Toast.makeText(this, R.string.compatibility_notification_permission_required, Toast.LENGTH_SHORT).show()
    }

    private val receiveSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshCompatibilityStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.compatibilityScrollview))
        setupMaterialScrollListener(binding.compatibilityScrollview, binding.compatibilityAppbar)
        setupTopAppBar(binding.compatibilityAppbar, NavigationIcon.Arrow)
        binding.compatibilityToolbar.title = ""

        binding.compatibilityDefaultSms.setOnClickListener { requestDefaultSmsRole() }
        binding.compatibilityPermissions.setOnClickListener { requestReceiveSmsPermission() }
        binding.compatibilityNotifications.setOnClickListener { fixNotifications() }
        binding.compatibilityAutostart.setOnClickListener { openAutoStartSettings() }
        binding.compatibilityBattery.setOnClickListener { requestUnrestrictedBattery() }
        binding.compatibilityContinueFix.setOnClickListener { continueFix() }
        binding.compatibilityRefresh.setOnClickListener {
            refreshCompatibilityStatus()
            Toast.makeText(this, R.string.compatibility_refreshed, Toast.LENGTH_SHORT).show()
        }
        binding.compatibilityTestNotification.setOnClickListener { sendTestNotification() }
        binding.compatibilityCopyReport.setOnClickListener { copyDiagnosticReport() }
        binding.compatibilityAdvancedToggle.setOnClickListener { toggleAdvancedDiagnostics() }
        binding.compatibilityCopyFix.setOnClickListener { copyAdbRepairCommands() }
        binding.compatibilityProject.setOnClickListener { openProjectRepository() }
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(LowBatteryCheckWorker.UNIQUE_PERIODIC)
            .observe(this) { workInfos ->
                val activeStates = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
                lowBatteryWorkState = (workInfos.firstOrNull { it.state in activeStates } ?: workInfos.lastOrNull())
                    ?.state
                    ?.let(::workStateLabel)
                    ?: "未调度"
                refreshFeatureStatus()
            }
        showBrandAdvice()
    }

    override fun onResume() {
        super.onResume()
        applyMiuiPageChrome()
        binding.compatibilityDevice.text = getString(
            R.string.compatibility_device,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.RELEASE,
        )
        refreshCompatibilityStatus()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_SMS_ROLE -> binding.root.postDelayed({
                refreshCompatibilityStatus()
                if (isDefaultSmsApp() && !isSmsChainReady()) requestLegacyDefaultSmsChange()
            }, ROLE_STATE_SETTLE_DELAY_MS)
            REQUEST_LEGACY_DEFAULT_SMS -> binding.root.postDelayed(
                { refreshCompatibilityStatus() },
                ROLE_STATE_SETTLE_DELAY_MS,
            )
        }
    }

    private fun refreshCompatibilityStatus() {
        val state = readState()
        val healthyCount = listOf(
            state.defaultSms,
            state.receiveSms,
            state.notifications,
            state.battery,
        ).count { it }
        binding.compatibilityHealthProgress.max = HEALTH_CHECK_COUNT
        binding.compatibilityHealthProgress.progress = healthyCount
        binding.compatibilityHealthCount.text = getString(
            R.string.compatibility_health_count,
            healthyCount,
            HEALTH_CHECK_COUNT,
        )
        val healthTitle = when {
            healthyCount == HEALTH_CHECK_COUNT -> R.string.compatibility_health_good
            healthyCount >= 2 -> R.string.compatibility_health_attention
            else -> R.string.compatibility_health_risk
        }
        val healthColor = when {
            healthyCount == HEALTH_CHECK_COUNT -> R.color.miui_action_blue
            healthyCount >= 2 -> android.R.color.holo_orange_dark
            else -> R.color.miui_unread_red
        }
        binding.compatibilityHealthTitle.setText(healthTitle)
        binding.compatibilityHealthTitle.setTextColor(ContextCompat.getColor(this, healthColor))
        binding.compatibilityContinueFix.text = getString(
            if (healthyCount == HEALTH_CHECK_COUNT) R.string.compatibility_all_ready
            else R.string.compatibility_continue_fix,
        )
        binding.compatibilityContinueFix.isEnabled = healthyCount < HEALTH_CHECK_COUNT

        setStatus(binding.compatibilityDefaultSmsStatus, state.defaultSms)
        setStatus(binding.compatibilityPermissionsStatus, state.receiveSms)
        binding.compatibilityPermissionsSummary.text = getString(
            R.string.compatibility_sms_permissions_detail,
            statusWord(state.receiveSms),
            statusWord(state.readSms),
            statusWord(state.sendSms),
        )
        setStatus(binding.compatibilityNotificationsStatus, state.notifications)
        binding.compatibilityNotificationsSummary.text = when {
            !state.notificationPermission -> getString(R.string.compatibility_notification_summary)
            !state.notificationChannel -> getString(R.string.compatibility_notification_channel_off)
            else -> getString(R.string.compatibility_notification_summary)
        }
        setStatus(binding.compatibilityBatteryStatus, state.battery)

        binding.compatibilityStatus.text = getString(
            R.string.compatibility_status,
            statusWord(state.defaultSms),
            state.routedPackage ?: getString(R.string.compatibility_unknown),
            statusWord(state.route),
            if (state.writeSms) getString(R.string.compatibility_allowed) else getString(R.string.compatibility_not_allowed),
            statusWord(state.battery),
            getString(if (state.smsChain) R.string.compatibility_chain_ready else R.string.compatibility_chain_split),
        )
        binding.compatibilityCopyFix.visibility = if (state.smsChain) View.GONE else View.VISIBLE
        refreshFeatureStatus()
    }

    private fun setStatus(view: TextView, ready: Boolean) {
        view.text = getString(if (ready) R.string.compatibility_ready else R.string.compatibility_not_ready)
        view.setTextColor(ContextCompat.getColor(this, if (ready) R.color.miui_action_blue else R.color.miui_unread_red))
    }

    private fun statusWord(ready: Boolean): String =
        getString(if (ready) R.string.compatibility_ready else R.string.compatibility_not_ready)

    private fun readState(): CompatibilityState {
        val defaultSms = isDefaultSmsApp()
        val routedPackage = getLegacySmsRoute()
        val route = routedPackage == packageName
        val writeSms = isWriteSmsAllowed()
        val receiveSms = hasPermission(Manifest.permission.RECEIVE_SMS)
        val readSms = hasPermission(Manifest.permission.READ_SMS)
        val sendSms = hasPermission(Manifest.permission.SEND_SMS)
        val notificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val notificationChannel = isSmsNotificationChannelEnabled()
        val battery = (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        return CompatibilityState(
            defaultSms = defaultSms,
            receiveSms = receiveSms,
            readSms = readSms,
            sendSms = sendSms,
            notificationPermission = notificationPermission,
            notificationChannel = notificationChannel,
            notifications = notificationPermission && notificationsEnabled && notificationChannel,
            battery = battery,
            routedPackage = routedPackage,
            route = route,
            writeSms = writeSms,
            smsChain = defaultSms && route && writeSms,
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isSmsNotificationChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = getSystemService(NotificationManager::class.java)
        val channel = manager?.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun continueFix() {
        val state = readState()
        when {
            !state.defaultSms -> requestDefaultSmsRole()
            !state.receiveSms -> requestReceiveSmsPermission()
            !state.notifications -> fixNotifications()
            !state.battery -> requestUnrestrictedBattery()
            else -> openAutoStartSettings()
        }
    }

    private fun requestReceiveSmsPermission() {
        if (!hasPermission(Manifest.permission.RECEIVE_SMS)) {
            receiveSmsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
        } else {
            openAppDetails()
        }
    }

    private fun fixNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        openNotificationSettings()
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.compatibility_notification_settings_unavailable, Toast.LENGTH_SHORT).show()
            openAppDetails()
        }
    }

    private fun sendTestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Toast.makeText(this, R.string.compatibility_notification_permission_required, Toast.LENGTH_SHORT).show()
            openNotificationSettings()
        } else {
            sendTestNotificationInternal()
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendTestNotificationInternal() {
        createTestNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(TEST_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
                openNotificationSettings()
                return
            }
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            TEST_NOTIFICATION_ID,
            Intent(this, DeviceCompatibilityActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, TEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.compatibility_test_notification_title))
            .setContentText(getString(R.string.compatibility_test_notification_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(TEST_NOTIFICATION_ID, notification) }
            .onSuccess { Toast.makeText(this, R.string.compatibility_test_notification_sent, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, R.string.compatibility_notification_permission_required, Toast.LENGTH_SHORT).show() }
    }

    private fun createTestNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            TEST_CHANNEL_ID,
            getString(R.string.compatibility_test_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = getString(R.string.compatibility_test_channel_description) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun copyDiagnosticReport() {
        val state = readState()
        val routeValue = when (state.routedPackage) {
            packageName -> getString(R.string.compatibility_ready)
            null -> getString(R.string.compatibility_unknown)
            else -> getString(R.string.compatibility_not_ready)
        }
        val baseReport = getString(
            R.string.compatibility_diagnostic_report,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE.toString(),
            statusWord(state.defaultSms),
            statusWord(state.receiveSms),
            statusWord(state.readSms),
            statusWord(state.sendSms),
            statusWord(state.notifications),
            statusWord(state.battery),
            routeValue,
            if (state.writeSms) getString(R.string.compatibility_allowed) else getString(R.string.compatibility_not_allowed),
        )
        val report = "$baseReport\n\n短信与转发功能自检（敏感配置已隐藏）\n${buildFeatureStatus()}"
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.compatibility_report_label), report))
        Toast.makeText(this, R.string.compatibility_report_copied, Toast.LENGTH_SHORT).show()
    }

    private fun toggleAdvancedDiagnostics() {
        advancedExpanded = !advancedExpanded
        binding.compatibilityAdvancedContent.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        binding.compatibilityAdvancedToggle.setText(
            if (advancedExpanded) R.string.compatibility_advanced_hide else R.string.compatibility_advanced_show,
        )
    }

    private fun refreshFeatureStatus() {
        binding.compatibilityFeatureStatus.text = buildFeatureStatus()
    }

    @SuppressLint("MissingPermission")
    private fun buildFeatureStatus(): String {
        val multi = MultiForwardConfig(applicationContext)
        val pushPlus = PushPlusConfig(applicationContext)
        val rules = ForwardingRulesConfig(applicationContext)
        val remoteSms = RemoteSmsCommandConfig(applicationContext)
        val history = ForwardingHistoryStore(applicationContext).records()
        val simSummary = runCatching {
            val subscriptions = getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                .orEmpty()
                .sortedBy { it.simSlotIndex }
            if (subscriptions.isEmpty()) {
                "未检测到可用 SIM 或缺少电话状态权限"
            } else {
                subscriptions.joinToString("、") { "SIM${it.simSlotIndex + 1} ${it.carrierName}" }
            }
        }.getOrElse { "读取失败或缺少电话状态权限" }

        val channelStates = listOf(
            configState("PushPlus", pushPlus.enabled, pushPlus.getToken().isNotBlank()),
            configState("钉钉", multi.dingTalkEnabled, multi.dingTalkWebhook().isNotBlank()),
            configState("飞书", multi.feishuEnabled, multi.feishuWebhook().isNotBlank()),
            configState("企业微信", multi.weComEnabled, multi.weComCorpId().isNotBlank() && multi.weComSecret().isNotBlank()),
            configState("企业微信群机器人", multi.weComBotEnabled, multi.weComBotWebhook().isNotBlank()),
            configState("邮箱", multi.emailEnabled, multi.emailHost().isNotBlank() && multi.emailUser().isNotBlank()),
            configState("短信直发", multi.smsDirectEnabled, multi.smsDirectPhone().isNotBlank()),
            configState("Bark", multi.barkEnabled, multi.barkServerUrl().isNotBlank() && multi.barkDeviceKey().isNotBlank()),
            configState("Gotify", multi.gotifyEnabled, multi.gotifyServerUrl().isNotBlank() && multi.gotifyToken().isNotBlank()),
        )
        val dingTalkRemote = when {
            !multi.dingTalkRemoteControlEnabled -> "关闭"
            multi.dingTalkRemoteClientId().isBlank() || multi.dingTalkRemoteClientSecret().isBlank() -> "开启但配置不完整"
            multi.dingTalkRemoteConnectionStatus.contains("已连接") -> "已连接"
            else -> "已开启，尚未确认连接"
        }
        val lowBattery = when {
            !config.enableLowBatteryReminder -> "关闭"
            config.lowBatteryChannels.isEmpty() -> "开启但未选择渠道"
            else -> "开启 · $lowBatteryWorkState · ${config.lowBatteryChannels.map(ForwardingChannels::displayName).joinToString("、")}"
        }
        val failedCount = history.count { it.status == ForwardingHistoryStore.STATUS_FAILED }
        return buildString {
            appendLine("SIM：$simSummary")
            appendLine("转发渠道：")
            channelStates.forEach { appendLine("  $it") }
            appendLine("转发规则：${if (rules.enabled) "开启 · ${rules.rules.count { it.enabled }} 条启用" else "关闭"}")
            appendLine("低电量提醒：$lowBattery")
            appendLine("短信远程指令：${if (remoteSms.enabled) "开启 · 白名单 ${remoteSms.authorizedList().size} 个" else "关闭"}")
            appendLine("钉钉远程指令：$dingTalkRemote")
            append("转发记录：${history.size} 条 · 失败 $failedCount 条")
        }
    }

    private fun configState(name: String, enabled: Boolean, complete: Boolean) = when {
        !enabled -> "$name(关闭)"
        complete -> "$name(配置完整)"
        else -> "$name(配置不完整)"
    }

    private fun workStateLabel(state: WorkInfo.State) = when (state) {
        WorkInfo.State.ENQUEUED -> "已调度"
        WorkInfo.State.RUNNING -> "正在检查"
        WorkInfo.State.BLOCKED -> "等待条件"
        WorkInfo.State.CANCELLED -> "已取消"
        WorkInfo.State.FAILED -> "任务失败"
        WorkInfo.State.SUCCEEDED -> "已完成"
    }

    private fun showBrandAdvice() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val (title, advice) = when {
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
                R.string.compatibility_brand_vivo_title to R.string.compatibility_brand_vivo_advice
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                R.string.compatibility_brand_huawei_title to R.string.compatibility_brand_huawei_advice
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                R.string.compatibility_brand_xiaomi_title to R.string.compatibility_brand_xiaomi_advice
            else -> R.string.compatibility_brand_other_title to R.string.compatibility_brand_other_advice
        }
        binding.compatibilityBrandTitle.setText(title)
        binding.compatibilityBrandAdvice.setText(advice)
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
        appOps?.checkOpNoThrow(WRITE_SMS_APP_OP, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
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

    private fun openProjectRepository() {
        launchOrFallback(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_REPOSITORY_URL)))
    }

    private fun requestDefaultSmsRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isDefaultSmsApp()) {
                if (!isSmsChainReady()) requestLegacyDefaultSmsChange()
                return
            }
            val intent = getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_SMS)
            if (intent != null) runCatching { startActivityForResult(intent, REQUEST_SMS_ROLE) }
                .onFailure { openAppDetails() }
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
        val brand = org.fossify.messages.helpers.DeviceCompatHelper.detectBrand()
        val opened = org.fossify.messages.helpers.DeviceCompatHelper.openAutoStartSettings(this, brand)
        if (!opened) openAppDetails()
    }

    private fun requestUnrestrictedBattery() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, R.string.compatibility_battery_already_ready, Toast.LENGTH_SHORT).show()
            return
        }
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
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private data class CompatibilityState(
        val defaultSms: Boolean,
        val receiveSms: Boolean,
        val readSms: Boolean,
        val sendSms: Boolean,
        val notificationPermission: Boolean,
        val notificationChannel: Boolean,
        val notifications: Boolean,
        val battery: Boolean,
        val routedPackage: String?,
        val route: Boolean,
        val writeSms: Boolean,
        val smsChain: Boolean,
    )

    private companion object {
        const val SMS_DEFAULT_APPLICATION_KEY = "sms_default_application"
        const val WRITE_SMS_APP_OP = "android:write_sms"
        const val TEST_CHANNEL_ID = "compatibility_test"
        const val TEST_NOTIFICATION_ID = 19084
        const val HEALTH_CHECK_COUNT = 4
        const val REQUEST_SMS_ROLE = 801
        const val REQUEST_LEGACY_DEFAULT_SMS = 802
        const val ROLE_STATE_SETTLE_DELAY_MS = 500L
        const val PROJECT_REPOSITORY_URL = "https://github.com/2756826865/sms-forwarder-huawei"
    }
}
