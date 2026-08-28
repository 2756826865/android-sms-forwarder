package org.fossify.messages.ui.compose.dashboard

import android.os.Build
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.compose.components.GatewayCard
import org.fossify.messages.ui.compose.components.MetricGauge
import org.fossify.messages.ui.compose.components.StatusBadge
import org.fossify.messages.ui.compose.theme.GatewayBlue
import org.fossify.messages.ui.compose.theme.GatewayGreen
import org.fossify.messages.ui.compose.theme.GatewayOrange
import org.fossify.messages.ui.compose.theme.GatewayPurple
import org.fossify.messages.ui.compose.theme.GatewayRed
import org.fossify.messages.ui.dashboard.DashboardViewModel
import org.fossify.messages.ui.dashboard.model.DashboardStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToOperations: () -> Unit = {},
    onSwitchToClassic: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadStats()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "SMS Gateway 自动化大盘",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "SMS Forwarder v1.1.2 · 节点保活态势感知",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Surface(
                        onClick = onSwitchToClassic,
                        color = androidx.compose.ui.graphics.Color(0xFFE8F5E9),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = "📱 经典版",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color(0xFF159447),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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
                        text = "加载大盘异常: ${state.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            is UiState.Success -> {
                DashboardContent(
                    stats = state.data,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    onRefresh = { viewModel.loadStats() },
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

@Composable
fun DashboardContent(
    stats: DashboardStats,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    onNavigateToOperations: () -> Unit = {},
    onSwitchToClassic: () -> Unit = {}
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

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
        healthScore >= 90 -> "🛡️ 7x24h 运行极佳 · 息屏无休眠风险"
        healthScore >= 70 -> "⚠️ 存在休眠风险 · 建议配置白名单"
        else -> "🚨 高危掉线风险 · 必须加白保活"
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(modifier = Modifier.height(2.dp)) }

        // 0. 最顶部置顶：一键切换到经典原生极简短信版
        item {
            Surface(
                color = androidx.compose.ui.graphics.Color(0xFFF0F8F3),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSwitchToClassic() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("📱", fontSize = 22.sp)
                        Column {
                            Text(
                                text = "切换到经典原生极简短信版",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = androidx.compose.ui.graphics.Color(0xFF0A4F24)
                            )
                            Text(
                                text = "点击 0.1 秒切回纯粹会话列表、MIUI 搜索栏与绿色 FAB",
                                style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color(0xFF2E7D32)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = androidx.compose.ui.graphics.Color(0xFF159447),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "立即切换 👉",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // 顶部保活预警条
        if (healthScore < 90) {
            item {
                Surface(
                    color = GatewayOrange.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToOperations() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚠️ 检测到当前手机存在息屏被杀风险 (保活得分: $healthScore)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = GatewayOrange
                            )
                            Text(
                                text = "请一键配置电池优化白名单与自启动，保障 7x24h 稳定转发",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("👉 修复", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GatewayOrange)
                    }
                }
            }
        }

        // 1. 网关运行节点与动态健康评分
        item {
            GatewayCard(
                title = "网关运行节点态势",
                badge = if (!stats.isBatteryOptimized) "ONLINE · 稳定运行" else "DEGRADED · 需加白",
                badgeColor = if (!stats.isBatteryOptimized) GatewayGreen else GatewayOrange
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "设备: ${Build.MANUFACTURER.uppercase()} ${Build.MODEL}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = scoreDescription,
                            style = MaterialTheme.typography.labelSmall,
                            color = scoreColor,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // 动态健康分仪表盘
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(56.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { (healthScore.toFloat() / 100f) },
                            modifier = Modifier.fillMaxSize(),
                            color = scoreColor,
                            strokeWidth = 5.dp,
                            trackColor = scoreColor.copy(alpha = 0.15f)
                        )
                        Text(
                            text = "$healthScore",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = scoreColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusBadge(
                        text = if (!stats.isBatteryOptimized) "已加入电池白名单" else "未忽略电池优化",
                        color = if (!stats.isBatteryOptimized) GatewayGreen else GatewayOrange
                    )
                    StatusBadge(
                        text = if (stats.isDefaultSmsApp) "默认短信就绪" else "未设为默认短信",
                        color = if (stats.isDefaultSmsApp) GatewayGreen else GatewayOrange
                    )
                    StatusBadge(
                        text = if (stats.isNotificationEnabled) "通知权限正常" else "通知未开启",
                        color = if (stats.isNotificationEnabled) GatewayGreen else GatewayOrange
                    )
                }
            }
        }

        // 2. 今日发信水压
        item {
            GatewayCard(title = "今日短信收发水压") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricGauge(
                        title = "总发信事实",
                        value = "${stats.todaySentCount}",
                        subtitle = "已落库事实",
                        progress = 1f,
                        indicatorColor = GatewayBlue,
                        modifier = Modifier.weight(1f)
                    )
                    MetricGauge(
                        title = "成功送达",
                        value = "${stats.todaySuccessCount}",
                        subtitle = "回执成功",
                        progress = if (stats.todaySentCount > 0) stats.todaySuccessCount.toFloat() / stats.todaySentCount else 0f,
                        indicatorColor = GatewayGreen,
                        modifier = Modifier.weight(1f)
                    )
                    MetricGauge(
                        title = "发信失败",
                        value = "${stats.todayFailedSendCount}",
                        subtitle = "基带拦截/异常",
                        progress = if (stats.todaySentCount > 0) stats.todayFailedSendCount.toFloat() / stats.todaySentCount else 0f,
                        indicatorColor = GatewayRed,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 3. 今日转发水压
        item {
            GatewayCard(title = "今日多渠道转发水压") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricGauge(
                        title = "转发成功",
                        value = "${stats.todayForwardSuccessCount}",
                        subtitle = "多通道送达",
                        indicatorColor = GatewayGreen,
                        modifier = Modifier.weight(1f)
                    )
                    MetricGauge(
                        title = "转发中/队列",
                        value = "${stats.todayForwardPendingCount}",
                        subtitle = "派发中",
                        indicatorColor = GatewayBlue,
                        modifier = Modifier.weight(1f)
                    )
                    MetricGauge(
                        title = "转发失败",
                        value = "${stats.todayForwardFailedCount}",
                        subtitle = "阻断/网络",
                        indicatorColor = GatewayOrange,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 4. Outbox 任务队列深度
        item {
            GatewayCard(
                title = "Outbox 任务队列深度",
                badge = if (stats.pendingOutboxCount == 0) "队列零积压" else "积压: ${stats.pendingOutboxCount}",
                badgeColor = if (stats.pendingOutboxCount == 0) GatewayGreen else GatewayOrange
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricGauge(
                        title = "待派发 (PENDING)",
                        value = "${stats.pendingOutboxCount}",
                        subtitle = "准实时调度",
                        indicatorColor = GatewayBlue,
                        modifier = Modifier.weight(1f)
                    )
                    MetricGauge(
                        title = "退避重试 (RETRY)",
                        value = "${stats.retryOutboxCount}",
                        subtitle = "指数退避中",
                        indicatorColor = GatewayOrange,
                        modifier = Modifier.weight(1f)
                    )
                    MetricGauge(
                        title = "终态失败 (FAILED)",
                        value = "${stats.failedOutboxCount}",
                        subtitle = "安全隔离",
                        indicatorColor = GatewayRed,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 5. 最新发送与转发记录
        item {
            GatewayCard(
                title = "📜 最新发送与转发记录",
                badge = if (stats.recentHistoryRecords.isNotEmpty()) "${stats.recentHistoryRecords.size} 条记录" else "暂无记录",
                badgeColor = if (stats.recentHistoryRecords.isNotEmpty()) GatewayBlue else GatewayOrange
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (stats.recentHistoryRecords.isEmpty()) {
                        Text(
                            text = "暂无近期发送/转发流水（收到短信或在通道页点击「发测试」后在此实时展示）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        stats.recentHistoryRecords.take(15).forEach { record ->
                            val statusColor = when (record.status) {
                                "success" -> GatewayGreen
                                "failed" -> GatewayRed
                                "retry" -> GatewayOrange
                                "running" -> GatewayBlue
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
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
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            if (record.isTest) {
                                                StatusBadge(text = "测试", color = GatewayPurple)
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = timeFormat.format(Date(record.updatedAt)),
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = statusText,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = statusColor
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = "${if (record.sender.isNotBlank()) "来自: ${record.sender} · " else ""}${record.body}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2
                                    )

                                    if (record.detail.isNotBlank() && record.detail != "发送成功" && record.detail != "已加入发送队列") {
                                        Text(
                                            text = "详情: ${record.detail}",
                                            fontSize = 10.sp,
                                            color = if (record.status == "failed") GatewayRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
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
            GatewayCard(title = "网关快捷运维与自愈") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("⚡ 刷新大盘数据")
                    }

                    OutlinedButton(
                        onClick = onNavigateToOperations,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("🛠️ 运维与白名单")
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
}
