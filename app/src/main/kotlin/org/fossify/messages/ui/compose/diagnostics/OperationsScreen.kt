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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.fossify.messages.helpers.DeviceCompatHelper
import org.fossify.messages.observability.bundle.DiagnosticBundleGenerator
import org.fossify.messages.observability.log.LogLevel
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.compose.components.GatewayCard
import org.fossify.messages.ui.compose.components.StatusBadge
import org.fossify.messages.ui.compose.theme.GatewayBlue
import org.fossify.messages.ui.compose.theme.GatewayGreen
import org.fossify.messages.ui.compose.theme.GatewayOrange
import org.fossify.messages.ui.compose.theme.GatewayPurple
import org.fossify.messages.ui.compose.theme.GatewayRed
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "运维控制与保活排障中心",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "厂商白名单直达 · 硬件诊断包 · 实时日志瀑布流",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is UiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }
            UiState.Idle -> {
                viewModel.loadDiagnostics()
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
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        // 1. 厂商保活与白名单一键直达向导 (OEM Whitelist Wizard)
        item {
            GatewayCard(
                title = "🛡️ 厂商后台保活与白名单直达",
                badge = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}",
                badgeColor = GatewayBlue
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "国内安卓系统在息屏后会激进杀后台。请配置以下两项，确保 7x24h 挂机不掉线：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 电池优化白名单一键加入
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🔋 电池优化白名单 (忽略省电限制)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    text = if (isIgnoringBattery) "✅ 已加入白名单（息屏不休眠）" else "⚠️ 未加入白名单（可能被系统休眠杀死）",
                                    fontSize = 11.sp,
                                    color = if (isIgnoringBattery) GatewayGreen else GatewayOrange
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
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = GatewayOrange)
                                ) {
                                    Text("一键加入", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // 厂商自启动一键跳转
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🚀 应用自启动 / 允许后台活动", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("关闭系统自动管理，开启允许自启动和后台运行", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = {
                                    val opened = DeviceCompatHelper.openAutoStartSettings(context)
                                    if (!opened) {
                                        Toast.makeText(context, "已打开应用详情，请在「权限」中允许后台运行", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("直达设置", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // 2. 硬件加密排障诊断包 (Encrypted Diagnostics Export)
        item {
            GatewayCard(
                title = "📦 硬件加密排障诊断包",
                badge = "KeyStore AES-256",
                badgeColor = GatewayPurple
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "一键聚合收集当前手机运行环境、7大底层依赖、Outbox 待发队列深度、自愈记录与脱敏日志。可用于自主排障或发送给技术支持进行深度分析。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val bundle = state.lastExportedBundle
                    if (bundle != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("✅ 诊断包已就绪", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GatewayGreen)
                                    StatusBadge(text = if (bundle.isEncrypted) "已硬件芯片加密" else "明文格式", color = GatewayPurple)
                                }
                                Text(
                                    text = "摘要签名 SHA-256: ${bundle.checksumSha256}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Text(
                                    text = "内容大小: ${bundle.bundleContent.length} 字符 | 保护级别: 硬件 KeyStore TEE 隔离",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                // 操作按钮：复制与分享
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("SMS_Diagnostic_Bundle", bundle.bundleContent))
                                            Toast.makeText(context, "诊断包内容已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("📋 复制数据", fontSize = 11.sp)
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
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("📤 一键分享", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 生成/刷新明文体检报告 (默认明文)
                        Button(
                            onClick = { viewModel.exportDiagnosticBundle(encryptWithKeyStore = false) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("📋 生成/刷新明文体检报告", fontSize = 12.sp)
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
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("🔍 全屏查看报告", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 3. RingBuffer 实时日志瀑布流 (Live Log Waterfall)
        item {
            GatewayCard(
                title = "RingBuffer 实时日志瀑布流",
                badge = "最新 50 条"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.recentLogs.isEmpty()) {
                        Text(
                            text = "暂无近期日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = log.level.name.take(1),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = levelColor,
                                        modifier = Modifier.width(16.dp)
                                    )
                                    Text(
                                        text = timeFormat.format(Date(log.timestamp)),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "[${log.tag}] ${log.message}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(100.dp)) }
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
                    }
                ) {
                    Text("复制全部报告")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}
