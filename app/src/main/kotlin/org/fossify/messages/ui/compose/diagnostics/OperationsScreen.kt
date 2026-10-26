package org.fossify.messages.ui.compose.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.fossify.messages.extensions.config
import org.fossify.messages.helpers.RecentTasksVisibility
import org.fossify.messages.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.fossify.messages.helpers.DeviceCompatHelper
import org.fossify.messages.observability.bundle.DiagnosticBundleGenerator
import org.fossify.messages.observability.log.LogLevel
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.compose.components.StatusBadge
import org.fossify.messages.ui.compose.theme.AppBackground
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.BrandGreenSoft
import org.fossify.messages.ui.compose.theme.DarkBackground
import org.fossify.messages.ui.compose.theme.DarkOutline
import org.fossify.messages.ui.compose.theme.DarkSurface
import org.fossify.messages.ui.compose.theme.GatewayBlue
import org.fossify.messages.ui.compose.theme.GatewayGreen
import org.fossify.messages.ui.compose.theme.GatewayOrange
import org.fossify.messages.ui.compose.theme.GatewayPurple
import org.fossify.messages.ui.compose.theme.GatewayRed
import org.fossify.messages.ui.compose.theme.OutlineSoft
import org.fossify.messages.ui.compose.theme.SurfaceCard
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary
import org.fossify.messages.ui.compose.theme.TextTertiary
import org.fossify.messages.ui.diagnostics.DiagnosticsViewModel
import org.fossify.messages.ui.diagnostics.model.DiagnosticsState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.services.SmsKeepAliveService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationsScreen(viewModel: DiagnosticsViewModel, initialSection: String? = null, navigationRequest: Int = 0) {
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(navigationRequest) {
        if (navigationRequest > 0) section = initialSection
    }
    androidx.activity.compose.BackHandler(enabled = section != null) { section = null }
    val settingsContext = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", LocalConfiguration.current.locales[0])
    if (section == "帮助与反馈") {
        DeveloperHelpPage(onBack = { section = null })
        return
    }
    if (section == "外观与主题") {
        if (org.fossify.messages.ui.compose.theme.LocalAppearance.current.material)
            org.fossify.messages.ui.compose.theme.MaterialAppearancePage(onBack = { section = null })
        else org.fossify.messages.ui.compose.theme.ColorPaletteScreenMiuix(onBack = { section = null })
        return
    }
    if (section == null) {
        if (org.fossify.messages.ui.compose.theme.LocalAppearance.current.material) MaterialSettingsHome(onOpen = { if (it == "完整设置") openSupportDestination(settingsContext, "app:settings") else section = it })
        else KsuSettingsHome(onOpen = { if (it == "完整设置") openSupportDestination(settingsContext, "app:settings") else section = it })
        return
    }
    KsuSettingsPage(section.orEmpty(), onBack = { section = null }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (section in listOf("外观与主题", "短信与显示", "备份与恢复")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                    .padding(bottom = org.fossify.messages.ui.compose.navigation.LocalGatewayBottomPadding.current),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (section) {
                        "外观与主题" -> org.fossify.messages.ui.compose.theme.AppearanceSettingsButton(embedded = true)
                        "短信与显示" -> DeveloperSettingsCard()
                        "备份与恢复" -> ConfigBackupCard()
                    }
                }
            } else {
                when (val state = uiState) {
                    is UiState.Success -> OperationsContent(state.data, viewModel, timeFormat,
                        modifier = Modifier.fillMaxSize(), section = section.orEmpty())
                    is UiState.Error -> Column(Modifier.padding(20.dp)) {
                        Text("诊断暂不可用：${state.message}", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.loadDiagnostics() }) { Text("重新加载") }
                    }
                    is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    UiState.Idle -> androidx.compose.runtime.LaunchedEffect(viewModel) { viewModel.loadDiagnostics() }
                }
            }
        }
    }
}

@Composable
fun OperationsContent(
    state: DiagnosticsState,
    viewModel: DiagnosticsViewModel,
    timeFormat: SimpleDateFormat,
    modifier: Modifier = Modifier,
    section: String = "诊断与报告"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showReportDialog by remember { mutableStateOf(false) }
    var plainReportText by remember { mutableStateOf("") }
    var showAllLogs by remember { mutableStateOf(false) }
    val isDark = org.fossify.messages.ui.compose.ksu.isInDarkTheme()
    val primaryTextColor = MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 检查电池白名单状态
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val isIgnoringBattery = remember(state.lastUpdated) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }
    }

    val multiForwardConfig = remember { MultiForwardConfig(context) }
    var keepAliveEnabled by remember { mutableStateOf(multiForwardConfig.keepAliveServiceEnabled) }
    var showServiceNotificationChannels by remember { mutableStateOf(false) }
    var hideRecentTasks by remember { mutableStateOf(context.config.hideFromRecentTasks) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hideRecentTasks = context.config.hideFromRecentTasks
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 10.dp,
            bottom = org.fossify.messages.ui.compose.navigation.LocalGatewayBottomPadding.current + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (section == "保活设置") {
        // 1. 厂商保活与白名单一键直达向导 (OEM Whitelist Wizard)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "已检测设备型号:",
                            fontSize = 12.sp,
                            color = secondaryTextColor
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isDark) Color(0xFF1E3A5F) else Color(0xFFE8F1FF)
                        ) {
                            Text(
                                text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = GatewayBlue,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "依次检查电池优化、自启动与前台服务。厂商系统仍可能限制后台运行。",
                        fontSize = 12.sp,
                        color = secondaryTextColor,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // 电池优化白名单一键加入
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, if (isDark) Color(0xFF2D333B) else Color(0xFFEEF2F6)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "🔋 电池优化",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = primaryTextColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isIgnoringBattery) "✅ 已加入白名单（息屏不休眠）" else "⚠️ 请加入白名单",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isIgnoringBattery) BrandGreen else GatewayOrange,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            if (!isIgnoringBattery) {
                                Button(
                                    onClick = {
                                        try {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                    data = Uri.parse("package:${context.packageName}")
                                                }
                                                context.startActivity(intent)
                                            }
                                        } catch (e: Exception) {
                                            try {
                                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "请在系统设置中搜索「电池优化」并允许本应用", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = GatewayOrange),
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Text("一键加入", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 厂商自启动一键跳转
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, if (isDark) Color(0xFF2D333B) else Color(0xFFEEF2F6)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "🚀 自启动设置",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = primaryTextColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "请允许自启关闭自动管理",
                                    fontSize = 11.sp,
                                    color = secondaryTextColor,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    val opened = DeviceCompatHelper.openAutoStartSettings(context)
                                    if (!opened) {
                                        Toast.makeText(context, "已打开应用详情，请在「权限」中允许后台运行", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft),
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text("直达设置", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = primaryTextColor, maxLines = 1, softWrap = false)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.settings_hide_recent_tasks), fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp, color = primaryTextColor)
                                Text(stringResource(R.string.settings_hide_recent_tasks_summary),
                                    fontSize = 11.sp, color = secondaryTextColor)
                            }
                            Switch(checked = hideRecentTasks, onCheckedChange = { hidden ->
                                hideRecentTasks = hidden
                                if (!RecentTasksVisibility.setHidden(context, hidden)) {
                                    Toast.makeText(context, R.string.settings_hide_recent_tasks_apply_failed, Toast.LENGTH_LONG).show()
                                }
                            })
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    // 前台常驻保活服务开关
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, if (isDark) Color(0xFF2D333B) else Color(0xFFEEF2F6)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "🛡️ 兼容保活模式",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = primaryTextColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (keepAliveEnabled) "常驻辅助保活，适合锁屏后漏收的设备" else "按需运行：收到短信后处理，完成后结束",
                                    fontSize = 11.sp,
                                    color = if (keepAliveEnabled) BrandGreen else secondaryTextColor,
                                    maxLines = 3
                                )
                            }
                            Switch(
                                checked = keepAliveEnabled,
                                onCheckedChange = { enabled ->
                                    keepAliveEnabled = enabled
                                    multiForwardConfig.keepAliveServiceEnabled = enabled
                                    if (!enabled) org.fossify.messages.security.root.RootWatchdog.stop(context)
                                    if (enabled) {
                                        SmsKeepAliveService.ensureStarted(context)
                                    } else {
                                        context.stopService(Intent(context, SmsKeepAliveService::class.java))
                                    }
                                }
                            )
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Spacer(modifier = Modifier.height(10.dp))

                        // Only service channels are listed here; incoming SMS notifications stay enabled.
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC),
                            border = BorderStroke(1.dp, if (isDark) Color(0xFF2D333B) else Color(0xFFEEF2F6)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = "🔔 服务运行通知",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = primaryTextColor,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "按服务设置常驻提示，保留新短信和验证码通知",
                                            fontSize = 11.sp,
                                            color = secondaryTextColor,
                                            maxLines = 2
                                        )
                                    }
                                    TextButton(onClick = { showServiceNotificationChannels = !showServiceNotificationChannels }) {
                                        Text(if (showServiceNotificationChannels) "收起" else "分别设置")
                                    }
                                }
                                if (showServiceNotificationChannels) {
                                    Text(
                                        "只选择下面的运行服务渠道；不要关闭应用通知总开关或‘收到的短信’渠道。系统可能仍在运行中应用列表显示前台服务。",
                                        fontSize = 11.sp,
                                        color = secondaryTextColor
                                    )
                                    listOf(
                                        "短信后台服务" to "sms_background_service",
                                        "短信接收处理中" to "incoming_sms_processing",
                                        "转发任务运行中" to "forwarding_delivery",
                                        "钉钉远程来源" to "dingtalk_remote_control",
                                        "企业微信远程来源" to "wecom_remote_control",
                                        "飞书远程来源" to "feishu_remote_control",
                                        "Telegram 远程来源" to "tg_remote_control",
                                        "WebSocket 远程来源" to "ws_remote_control",
                                        "邮箱远程来源" to "email_remote_control"
                                    ).forEach { (label, channelId) ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp, color = primaryTextColor)
                                            TextButton(onClick = { openServiceNotificationChannel(context, channelId) }) {
                                                Text("设置")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        }
        if (section == "诊断与报告") {
        item { OperationsGroupHeading("02", "链路诊断", "按需检查，同步状态随时可见") }
        item { SmsChainDiagnosticsCard() }

        // 2. 硬件加密排障诊断包 (Encrypted Diagnostics Export)
        item { OperationsGroupHeading("03", "诊断报告", "先查看内容，再选择复制或分享") }
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📦 运行报告",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else TextPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDark) Color(0xFF2C1E3A) else Color(0xFFF3E8FF)
                        ) {
                            Text(
                                text = "本机生成",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GatewayPurple,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "汇总设备环境、队列状态与近期日志。默认生成可阅读的明文报告，分享前请检查内容。",
                        fontSize = 12.sp,
                        color = secondaryTextColor,
                        lineHeight = 18.sp
                    )

                    val bundle = state.lastExportedBundle
                    if (bundle != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC),
                            border = BorderStroke(1.dp, if (isDark) Color(0xFF2D333B) else Color(0xFFEEF2F6)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("✅ 诊断包已就绪", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isDark) Color(0xFF2C1E3A) else Color(0xFFF3E8FF)
                                    ) {
                                        Text(
                                            text = if (bundle.isEncrypted) "Keystore 加密" else "明文格式",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GatewayPurple,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "内容校验 SHA-256: ${bundle.checksumSha256}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = secondaryTextColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "内容大小：${bundle.bundleContent.length} 字符 · ${if (bundle.isEncrypted) "Keystore 加密" else "未加密"}",
                                    fontSize = 11.sp,
                                    color = primaryTextColor
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                // 操作按钮：复制与分享
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("SMS_Diagnostic_Bundle", bundle.bundleContent))
                                            Toast.makeText(context, "诊断包内容已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f).height(40.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text("📋 复制数据", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = primaryTextColor, maxLines = 1, softWrap = false)
                                    }

                                    Button(
                                        onClick = {
                                            val sendIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, bundle.bundleContent)
                                                putExtra(Intent.EXTRA_TITLE, "SMS Forwarder 节点诊断数据")
                                                type = "text/plain"
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, "分享排障诊断数据"))
                                        },
                                        modifier = Modifier.weight(1f).height(40.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text("📤 一键分享", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 生成/刷新明文体检报告 (默认明文)
                        Button(
                            onClick = { viewModel.exportDiagnosticBundle(encryptWithKeyStore = false) },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
                        ) {
                            Text("📋 生成体检报告", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
                        }

                        // 查看明文弹窗
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val plain = DiagnosticBundleGenerator.generateBundle(context, encryptWithKeyStore = false)
                                    plainReportText = plain.bundleContent
                                    showReportDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft)
                        ) {
                            Text("🔍 全屏查看报告", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = primaryTextColor, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        item { OperationsGroupHeading("06", "运行日志", "最近事件，仅供排查") }
        // 3. RingBuffer 实时日志瀑布流 (Live Log Waterfall)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "运行日志",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else TextPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDark) Color(0xFF1E3A5F) else Color(0xFFE8F1FF)
                        ) {
                            Text(
                                text = "最近 ${minOf(state.recentLogs.size, 25)} 条",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GatewayBlue,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (state.recentLogs.isEmpty()) {
                        Text(
                            text = "暂无近期日志",
                            fontSize = 12.sp,
                            color = secondaryTextColor,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        state.recentLogs.takeLast(if (showAllLogs) 25 else 5).reversed().forEach { log ->
                            val levelColor = when (log.level) {
                                LogLevel.ERROR, LogLevel.CRITICAL -> GatewayRed
                                LogLevel.WARN -> GatewayOrange
                                LogLevel.INFO -> GatewayBlue
                                else -> Color.Gray
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC),
                                border = BorderStroke(1.dp, if (isDark) Color(0xFF2D333B) else Color(0xFFEEF2F6)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = log.level.name.take(1),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = levelColor,
                                        modifier = Modifier.width(18.dp)
                                    )
                                    Text(
                                        text = timeFormat.format(Date(log.timestamp)),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp,
                                        color = secondaryTextColor
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "[${log.tag}] ${log.message}",
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = primaryTextColor,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (state.recentLogs.size > 5) {
                            TextButton(onClick = { showAllLogs = !showAllLogs }) {
                                Text(if (showAllLogs) "收起日志" else "查看最近 25 条")
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
        }
    }

    // 明文体检报告弹窗
    if (showReportDialog) {
        AlertDialog(
            modifier = Modifier.navigationBarsPadding(),
            onDismissRequest = { showReportDialog = false },
            title = {
                Text("🔍 完整报告", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(modifier = Modifier.height(380.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = plainReportText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SMS_Plain_Report", plainReportText))
                        Toast.makeText(context, "明文体检报告已复制！", Toast.LENGTH_SHORT).show()
                        showReportDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("复制全部报告", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("关闭", color = secondaryTextColor)
                }
            }
        )
    }
}

@Composable
private fun OperationsGroupHeading(number: String, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                number,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
        Column {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

private fun openServiceNotificationChannel(context: Context, channelId: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(channelId) == null) {
        Toast.makeText(context, "该服务尚未运行，系统通知渠道还未创建", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "无法打开该服务的通知渠道设置", Toast.LENGTH_SHORT).show()
    }
}
