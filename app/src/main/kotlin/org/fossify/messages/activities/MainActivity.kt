package org.fossify.messages.activities

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.fossify.messages.BuildConfig
import org.fossify.messages.extensions.config
import org.fossify.messages.messaging.SmsRecoveryWorker
import org.fossify.messages.services.SmsKeepAliveService
import org.fossify.messages.ui.compose.conversations.ConversationsViewModel
import org.fossify.messages.ui.compose.navigation.GatewayApp
import org.fossify.messages.ui.compose.theme.GatewayTheme
import org.fossify.messages.ui.dashboard.DashboardViewModel
import org.fossify.messages.ui.diagnostics.DiagnosticsViewModel
import org.fossify.messages.ui.messages.MessageCenterViewModel
import org.fossify.messages.ui.messages.repository.MessageCenterRepository
import org.fossify.messages.ui.messages.usecase.GetMessageHistoryUseCase
import org.fossify.messages.ui.repository.DashboardDataRepository
import org.fossify.messages.ui.usecase.GetDashboardStatsUseCase
import org.fossify.messages.ui.usecase.GetRecoveryRecordsUseCase
import org.fossify.messages.ui.usecase.RunManualRecoveryUseCase

/**
 * SMS Forwarder 主控台入口 Activity (SMS Gateway Cockpit)
 *
 * 核心能力：
 * 1. 自动检测并动态申请 SEND_SMS, READ_SMS, READ_PHONE_STATE 核心权限；
 * 2. 引导用户一键将本应用设为系统默认短信应用（Default SMS App）；
 * 3. 纯 Jetpack Compose + Material 3 驱动 5 大业务控制台；
 * 4. 启动即自动调度 SmsKeepAliveService 与 SmsRecoveryWorker 自愈守护。
 */
class MainActivity : SimpleActivity() {

    private lateinit var dashboardViewModel: DashboardViewModel
    private lateinit var messageCenterViewModel: MessageCenterViewModel
    private lateinit var diagnosticsViewModel: DiagnosticsViewModel
    private lateinit var conversationsViewModel: ConversationsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 初始化网关运行会话
        config.appId = BuildConfig.APPLICATION_ID
        config.appRunCount++

        // 2. 确保后台保活与自愈守护常驻运行
        SmsKeepAliveService.ensureStarted(applicationContext)
        SmsRecoveryWorker.schedule(applicationContext)

        // 3. 自动检查并申请核心短信与电话权限
        checkAndRequestPermissions()

        // 4. 构建五大中枢 UseCases 与 ViewModels
        val dashboardRepo = DashboardDataRepository(applicationContext)
        val messageRepo = MessageCenterRepository(applicationContext)

        dashboardViewModel = DashboardViewModel(GetDashboardStatsUseCase(dashboardRepo))
        messageCenterViewModel = MessageCenterViewModel(GetMessageHistoryUseCase(messageRepo))
        diagnosticsViewModel = DiagnosticsViewModel(
            context = applicationContext,
            getRecoveryRecordsUseCase = GetRecoveryRecordsUseCase(dashboardRepo),
            runManualRecoveryUseCase = RunManualRecoveryUseCase(applicationContext)
        )
        conversationsViewModel = ConversationsViewModel(application)

        // 5. 纯 Compose 声明式渲染
        setContent {
            GatewayTheme {
                GatewayApp(
                    dashboardViewModel = dashboardViewModel,
                    messageCenterViewModel = messageCenterViewModel,
                    diagnosticsViewModel = diagnosticsViewModel,
                    conversationsViewModel = conversationsViewModel,
                    onRequestDefaultSmsRole = { requestDefaultSmsApp() }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    fun requestDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true && !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                @Suppress("DEPRECATION")
                startActivityForResult(intent, 1002)
            }
        } else {
            if (Telephony.Sms.getDefaultSmsPackage(this) != packageName) {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                }
                startActivity(intent)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::dashboardViewModel.isInitialized) {
            dashboardViewModel.loadStats()
        }
        if (::conversationsViewModel.isInitialized) {
            conversationsViewModel.refresh(isInitial = false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::dashboardViewModel.isInitialized) {
            dashboardViewModel.loadStats()
        }
        if (::messageCenterViewModel.isInitialized) {
            messageCenterViewModel.loadMessageHistory()
        }
        if (::diagnosticsViewModel.isInitialized) {
            diagnosticsViewModel.loadDiagnostics()
        }
        if (::conversationsViewModel.isInitialized) {
            conversationsViewModel.refresh(isInitial = false)
        }
    }
}
