package org.fossify.messages.ui.compose.dashboard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.BuildConfig
import org.fossify.messages.R
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.compose.components.GatewayCard
import org.fossify.messages.ui.compose.components.MetricGauge
import org.fossify.messages.ui.compose.components.StatusBadge
import org.fossify.messages.ui.compose.theme.AppBackground
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.BrandGreenDark
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
import org.fossify.messages.ui.compose.theme.RefreshIconBlue
import org.fossify.messages.ui.compose.theme.SurfaceCard
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary
import org.fossify.messages.ui.compose.theme.TextTertiary
import org.fossify.messages.ui.dashboard.DashboardViewModel
import org.fossify.messages.ui.dashboard.model.DashboardStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onRequestDefaultSms: () -> Unit = {},
    onNavigateToOperations: () -> Unit = {},
    onSwitchToClassic: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDark = isSystemInDarkTheme()
    val pageBgColor = if (isDark) DarkBackground else AppBackground
    val primaryTextColor = if (isDark) Color.White else TextPrimary
    val secondaryTextColor = if (isDark) Color(0xFF9CA3AF) else TextSecondary

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadStats()
    }

    Scaffold(
        containerColor = pageBgColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            // 顶部 Header (紧凑舒适排版)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                    Text(
                        text = "节点大盘",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryTextColor,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "版本 v${BuildConfig.VERSION_NAME} · 节点保活态势感知",
                        fontSize = 12.sp,
                        color = secondaryTextColor,
                        maxLines = 2,
                        softWrap = true
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        onClick = onSwitchToClassic,
                        shape = RoundedCornerShape(18.dp),
                        color = if (isDark) Color(0xFF1B3322) else BrandGreenSoft,
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📱 经典版",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = BrandGreen,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Surface(
                        onClick = { viewModel.loadStats() },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isDark) DarkSurface else Color.White,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_refresh_modern),
                                contentDescription = "刷新",
                                tint = RefreshIconBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
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
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "加载大盘异常: ${state.message}",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                is UiState.Success -> {
                    DashboardContent(
                        stats = state.data,
                        modifier = Modifier.fillMaxSize(),
                        onRefresh = { viewModel.loadStats() },
                        onRequestDefaultSms = onRequestDefaultSms,
                        onNavigateToOperations = onNavigateToOperations,
                        onSwitchToClassic = onSwitchToClassic
                    )
                }
                UiState.Idle -> {
                    viewModel.loadStats()
                }
            }
        }
    }
}

@Composable
fun DashboardContent(
    stats: DashboardStats,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    onRequestDefaultSms: () -> Unit = {},
    onNavigateToOperations: () -> Unit = {},
    onSwitchToClassic: () -> Unit = {}
) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val isDark = isSystemInDarkTheme()

    // 动态计算真实保活健康指数 (Keep-Alive Health Score 0~100)
    val healthScore = remember(stats) {
        var score = 100
        if (!stats.isDefaultSmsApp) score -= 30
        if (stats.isBatteryOptimized) score -= 25
        if (!stats.isNotificationEnabled) score -= 20
        if (stats.failedOutboxCount > 0) score -= 15
        if (stats.todayFailedSendCount > 0) score -= 10
        score.coerceIn(10, 100)
    }

    val scoreColor = when {
        healthScore >= 90 -> GatewayGreen
        healthScore >= 70 -> GatewayOrange
        else -> GatewayRed
    }

    val scoreDescription = when {
        healthScore >= 90 -> "🛡️ 运行极佳 · 息屏无休眠风险"
        healthScore >= 70 -> "⚠️ 存在休眠风险 · 请点击下面黄色字"
        else -> "🚨 高危掉线 · 请点击下面黄色字"
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 0. 最顶部置顶：一键切换到经典版 (单排紧凑)
        item {
            Surface(
                color = if (isDark) Color(0xFF162A1F) else Color(0xFFF0F9F3),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (isDark) Color(0xFF1E422C) else Color(0xFFD6F0DE)),
                shadowElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSwitchToClassic() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("📱", fontSize = 18.sp)
                        Text(
                            text = "切换到经典版",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFE8F5E9) else Color(0xFF0A4F24),
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Surface(
                        color = BrandGreen,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "立即切换 👉",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        // 顶部保活预警条 (单排紧凑，动态显示得分)
        if (healthScore < 90) {
            item {
                Surface(
                    color = GatewayOrange.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GatewayOrange.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToOperations() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "⚠️ 当前手机保活得分: $healthScore",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = GatewayOrange,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = "👉 修复",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = GatewayOrange,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // 1. 网关运行节点与动态健康评分
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) DarkSurface else SurfaceCard,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(15.dp)) {
                    // 卡片标题行 + 右侧状态 Chip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "网关运行节点态势",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else TextPrimary
                        )

                        val isOnline = !stats.isBatteryOptimized
                        val badgeBg = if (isOnline) (if (isDark) Color(0xFF1B3322) else BrandGreenSoft) else Color(0xFFFFF3E0)
                        val badgeColor = if (isOnline) BrandGreen else GatewayOrange
                        val badgeText = if (isOnline) "● 运行正常" else "● 状态受限"

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = badgeBg
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "设备: ${Build.MANUFACTURER.uppercase()} ${Build.MODEL}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color.White else TextPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                                fontSize = 12.sp,
                                color = if (isDark) Color(0xFF9CA3AF) else TextSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = scoreDescription,
                                fontSize = 12.sp,
                                color = scoreColor,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 动态健康分仪表盘
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(68.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { (healthScore.toFloat() / 100f) },
                                modifier = Modifier.fillMaxSize(),
                                color = scoreColor,
                                strokeWidth = 6.dp,
                                trackColor = scoreColor.copy(alpha = 0.15f)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$healthScore",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = scoreColor
                                )
                                Text(
                                    text = "健康分",
                                    fontSize = 8.5.sp,
                                    color = if (isDark) Color(0xFF9CA3AF) else TextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3 个状态横向标签 (等宽分布，点击可跳转对应系统设置)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val isBatteryOk = !stats.isBatteryOptimized
                        val isSmsOk = stats.isDefaultSmsApp
                        val isNotifOk = stats.isNotificationEnabled

                        StatusChipItem(
                            icon = "🔋",
                            text = if (isBatteryOk) "已加白名单" else "未设白名单",
                            isOk = isBatteryOk,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } else {
                                        onNavigateToOperations()
                                    }
                                } catch (e: Exception) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                    } catch (_: Exception) {
                                        onNavigateToOperations()
                                    }
                                }
                            }
                        )
                        StatusChipItem(
                            icon = "💬",
                            text = if (isSmsOk) "默认短信" else "未设默认",
                            isOk = isSmsOk,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onRequestDefaultSms()
                            }
                        )
                        StatusChipItem(
                            icon = "🔔",
                            text = if (isNotifOk) "通知正常" else "通知未开",
                            isOk = isNotifOk,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                try {
                                    val intent = Intent().apply {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        } else {
                                            action = "android.settings.APP_NOTIFICATION_SETTINGS"
                                            putExtra("app_package", context.packageName)
                                            putExtra("app_uid", context.applicationInfo.uid)
                                        }
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "请在系统设置中允许本应用的通知权限", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }

        // 2. 今日短信收发水压
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) DarkSurface else SurfaceCard,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(15.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isDark) Color(0xFF1B3322) else BrandGreenSoft,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text("📶", fontSize = 14.sp)
                            }
                        }
                        Text(
                            text = "今日短信收发水压",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatMetricColumn(
                            title = "总发信事实",
                            value = "${stats.todaySentCount}",
                            icon = "🚀",
                            color = GatewayBlue,
                            modifier = Modifier.weight(1f)
                        )
                        StatMetricColumn(
                            title = "成功送达",
                            value = "${stats.todaySuccessCount}",
                            icon = "✅",
                            color = GatewayGreen,
                            modifier = Modifier.weight(1f)
                        )
                        StatMetricColumn(
                            title = "发信失败",
                            value = "${stats.todayFailedSendCount}",
                            icon = "⚠️",
                            color = GatewayRed,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 3. 今日多渠道转发水压
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) DarkSurface else SurfaceCard,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(15.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isDark) Color(0xFF1E3A5F) else Color(0xFFE8F1FF),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text("🌐", fontSize = 14.sp)
                            }
                        }
                        Text(
                            text = "今日多渠道转发水压",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatMetricColumn(
                            title = "转发成功",
                            value = "${stats.todayForwardSuccessCount}",
                            icon = "✈️",
                            color = GatewayGreen,
                            modifier = Modifier.weight(1f)
                        )
                        StatMetricColumn(
                            title = "转发中/队列",
                            value = "${stats.todayForwardPendingCount}",
                            icon = "📦",
                            color = GatewayBlue,
                            modifier = Modifier.weight(1f)
                        )
                        StatMetricColumn(
                            title = "转发失败",
                            value = "${stats.todayForwardFailedCount}",
                            icon = "❌",
                            color = GatewayOrange,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 4. Outbox 任务队列深度
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) DarkSurface else SurfaceCard,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(15.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "发信任务队列深度",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else TextPrimary
                        )

                        val isZero = stats.pendingOutboxCount == 0
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isZero) (if (isDark) Color(0xFF1B3322) else BrandGreenSoft) else Color(0xFFFFF3E0)
                        ) {
                            Text(
                                text = if (isZero) "队列零积压" else "积压: ${stats.pendingOutboxCount}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isZero) BrandGreen else GatewayOrange,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatMetricColumn(
                            title = "待发送",
                            value = "${stats.pendingOutboxCount}",
                            icon = "⏳",
                            color = GatewayBlue,
                            modifier = Modifier.weight(1f)
                        )
                        StatMetricColumn(
                            title = "重试中",
                            value = "${stats.retryOutboxCount}",
                            icon = "🔄",
                            color = GatewayOrange,
                            modifier = Modifier.weight(1f)
                        )
                        StatMetricColumn(
                            title = "失败",
                            value = "${stats.failedOutboxCount}",
                            icon = "🚫",
                            color = GatewayRed,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 5. 最新发送与转发记录
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) DarkSurface else SurfaceCard,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(15.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📜 最新发送与转发记录",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else TextPrimary
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (stats.recentHistoryRecords.isNotEmpty()) (if (isDark) Color(0xFF1E3A5F) else Color(0xFFE8F1FF)) else Color(0xFFF3F4F6)
                        ) {
                            Text(
                                text = if (stats.recentHistoryRecords.isNotEmpty()) "${stats.recentHistoryRecords.size} 条记录" else "暂无记录",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (stats.recentHistoryRecords.isNotEmpty()) GatewayBlue else TextSecondary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (stats.recentHistoryRecords.isEmpty()) {
                        Text(
                            text = "暂无近期发送/转发流水（收到短信或在通道页点击「发测试」后在此实时展示）",
                            fontSize = 12.sp,
                            color = if (isDark) Color(0xFF9CA3AF) else TextSecondary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        stats.recentHistoryRecords.take(15).forEach { record ->
                            val statusColor = when (record.status) {
                                "success" -> GatewayGreen
                                "failed" -> GatewayRed
                                "retry" -> GatewayOrange
                                "running" -> GatewayBlue
                                else -> if (isDark) Color(0xFF9CA3AF) else TextSecondary
                            }
                            val statusText = when (record.status) {
                                "success" -> "✅ 成功"
                                "failed" -> "❌ 失败"
                                "retry" -> "🔄 重试"
                                "running" -> "⚡ 发送中"
                                "skipped" -> "⏭️ 跳过"
                                else -> "⏳ 队列"
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC),
                                border = BorderStroke(1.dp, if (isDark) Color(0xFF2D333B) else Color(0xFFEEF2F6)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.5.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = ForwardingChannels.displayName(record.channel),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = if (isDark) Color.White else TextPrimary
                                            )
                                            if (record.isTest) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = if (isDark) Color(0xFF331F4A) else Color(0xFFF3E8FF)
                                                ) {
                                                    Text(
                                                        text = "测试",
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = GatewayPurple,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = timeFormat.format(Date(record.updatedAt)),
                                                fontSize = 10.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (isDark) Color(0xFF9CA3AF) else TextSecondary
                                            )
                                            Text(
                                                text = statusText,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = statusColor
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(3.dp))

                                    Text(
                                        text = "${if (record.sender.isNotBlank()) "来自: ${record.sender} · " else ""}${record.body}",
                                        fontSize = 12.sp,
                                        color = if (isDark) Color(0xFFD1D5DB) else Color(0xFF374151),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    if (record.detail.isNotBlank() && record.detail != "发送成功" && record.detail != "已加入发送队列") {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "详情: ${record.detail}",
                                            fontSize = 10.5.sp,
                                            color = if (record.status == "failed") GatewayRed else (if (isDark) Color(0xFF9CA3AF) else TextSecondary),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. 快捷操作
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) DarkSurface else SurfaceCard,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(15.dp)) {
                    Text(
                        text = "网关快捷运维与自愈",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color.White else TextPrimary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onRefresh,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
                        ) {
                            Text(
                                text = "⚡ 刷新大盘数据",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        OutlinedButton(
                            onClick = onNavigateToOperations,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft)
                        ) {
                            Text(
                                text = "🛠️ 运维与白名单",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color.White else TextPrimary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(110.dp)) }
    }
}

@Composable
private fun StatusChipItem(
    icon: String,
    text: String,
    isOk: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()
    val bg = if (isOk) (if (isDark) Color(0xFF1B3322) else BrandGreenSoft) else (if (isDark) Color(0xFF332014) else Color(0xFFFFF3E0))
    val textColor = if (isOk) BrandGreen else GatewayOrange

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = modifier.height(34.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = icon, fontSize = 11.sp)
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = text,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatMetricColumn(
    title: String,
    value: String,
    icon: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 11.5.sp,
            color = if (isDark) Color(0xFF9CA3AF) else TextSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false
        )
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = color.copy(alpha = if (isDark) 0.2f else 0.12f),
            modifier = Modifier.size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(text = icon, fontSize = 14.sp)
            }
        }
    }
}
