package org.fossify.messages.ui.compose.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationsScreen(
    viewModel: DiagnosticsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    val isDark = isSystemInDarkTheme()
    val pageBgColor = if (isDark) DarkBackground else AppBackground
    val primaryTextColor = if (isDark) Color.White else TextPrimary
    val secondaryTextColor = if (isDark) Color(0xFF9CA3AF) else TextSecondary

    Scaffold(
        containerColor = pageBgColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            // 顶部 Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "运维控制与保活排障中心",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryTextColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "厂商白名单直达 · 硬件诊断包 · 实时日志瀑布流",
                        fontSize = 12.sp,
                        color = secondaryTextColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            when (val state = uiState) {
                is UiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = BrandGreen)
                    }
                }
                is UiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "加载诊断数据失败: ${state.message}",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                is UiState.Success -> {
                    OperationsContent(
                        state = state.data,
                        viewModel = viewModel,
                        timeFormat = timeFormat,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                UiState.Idle -> {
                    viewModel.loadDiagnostics()
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showReportDialog by remember { mutableStateOf(false) }
    var plainReportText by remember { mutableStateOf("") }
    val isDark = isSystemInDarkTheme()
    val primaryTextColor = if (isDark) Color.White else TextPrimary
    val secondaryTextColor = if (isDark) Color(0xFF9CA3AF) else TextSecondary

    // 检查电池白名单状态
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val isIgnoringBattery = remember(state.lastUpdated) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 厂商保活与白名单一键直达向导 (OEM Whitelist Wizard)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (isDark) DarkSurface else SurfaceCard,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "🛡️ 厂商后台保活与白名单直达",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color.White else TextPrimary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

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
                        text = "国内安卓系统在息屏后会激进杀后台。请配置以下两项，确保 7x24h 挂机不掉线：",
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
                                    text = "🔋 电池优化白名单 (忽略省电限制)",
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
                                    text = "🚀 应用自启动 / 允许后台活动",
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
                }
            }
        }

        // 2. 硬件加密排障诊断包 (Encrypted Diagnostics Export)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (isDark) DarkSurface else SurfaceCard,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📦 硬件加密排障诊断包",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else TextPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDark) Color(0xFF2C1E3A) else Color(0xFFF3E8FF)
                        ) {
                            Text(
                                text = "KeyStore AES-256",
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
                        text = "一键聚合收集当前手机运行环境、7大底层依赖、Outbox 待发队列深度、自愈记录与脱敏日志。可用于自主排障或发送给技术支持进行深度分析。",
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
                                    Text("✅ 诊断包已就绪", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BrandGreen)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isDark) Color(0xFF2C1E3A) else Color(0xFFF3E8FF)
                                    ) {
                                        Text(
                                            text = if (bundle.isEncrypted) "已硬件芯片加密" else "明文格式",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GatewayPurple,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "摘要签名 SHA-256: ${bundle.checksumSha256}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = secondaryTextColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "内容大小: ${bundle.bundleContent.length} 字符 | 保护级别: 硬件 KeyStore TEE 隔离",
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

        // 3. RingBuffer 实时日志瀑布流 (Live Log Waterfall)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (isDark) DarkSurface else SurfaceCard,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RingBuffer 实时日志瀑布流",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else TextPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDark) Color(0xFF1E3A5F) else Color(0xFFE8F1FF)
                        ) {
                            Text(
                                text = "最新 50 条",
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
                        state.recentLogs.takeLast(25).reversed().forEach { log ->
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
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(120.dp)) }
    }

    // 明文体检报告弹窗
    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = {
                Text("🔍 节点全量明文体检报告", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
