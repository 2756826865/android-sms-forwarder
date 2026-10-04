package org.fossify.messages.ui.compose.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.fossify.messages.extensions.getMessagesDB
import java.text.DateFormat
import java.util.Date

private data class DiagnosticPanel(val title: String, val body: String, val status: String = "只读结果", val warning: Boolean = false)

/** On-demand, bounded read-only evidence, not a replay or repair command. */
@Composable
fun SmsChainDiagnosticsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fallbackPrimary by remember { mutableStateOf<String?>(null) }
    val forwardingConfig = remember { org.fossify.messages.forwarding.MultiForwardConfig(context) }
    val instances = forwardingConfig.channelInstances()
    val fallbackConfig = remember { org.fossify.messages.forwarding.ForwardingFallbackConfig(context) }
    if (fallbackPrimary != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { fallbackPrimary = null },
            title = { Text("选择备用通道（仅发送前不可用时）") },
            text = { Column {
                Text("网络请求结果未知不切换；不自动使用收费短信通道。")
                instances.filter { it.id != fallbackPrimary && it.enabled && it.hasDispatchConfiguration() && it.channelType != org.fossify.messages.forwarding.ForwardingChannels.SMS_DIRECT }.take(20).forEach { item ->
                    androidx.compose.material3.TextButton(onClick = { fallbackConfig.set(fallbackPrimary!!, item.id); fallbackPrimary = null }) { Text(item.name) }
                }
            } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { fallbackConfig.set(fallbackPrimary!!, ""); fallbackPrimary = null }) { Text("关闭兜底") } })
    }
    var checkedAt by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(listOf(DiagnosticPanel("短信链路", "点击检查最近10条影子记录；记录缺失不代表业务失败。", "待检查"))) }
    val sources by remember { org.fossify.messages.remote.repository.RemoteSourceRepository.getInstance(context) }.sourcesFlow.collectAsState()
    val sync by org.fossify.messages.helpers.SmsSyncProgress.state.collectAsState()
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        OperationsSection("短信同步") {
        DiagnosticBadge(if (sync.running) "同步中" else if (sync.failed > 0) "有失败" else "空闲", sync.failed > 0)
        Text("短信同步：${if (sync.running) "进行中" else "空闲"} · ${sync.completed}/${sync.total} 个会话 · ${sync.failed} 个失败")
        }
        OperationsSection("链路诊断") {
        Text(if (checkedAt > 0) "最近检查：${DateFormat.getDateTimeInstance().format(Date(checkedAt))}" else "尚未检查", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        Text("仅显示步骤和状态，不显示号码、正文或凭据；通道受理不等于设备送达。")
        OutlinedButton(enabled = !loading, onClick = {
            loading = true
            scope.launch {
                try {
                    report = withContext(Dispatchers.IO) {
                        val dao = context.getMessagesDB().ShadowDaos()
                        val issues = org.fossify.messages.forwarding.ForwardingConfigAudit.inspect(
                            forwardingConfig.channelInstances(), org.fossify.messages.forwarding.ForwardingRulesConfig(context).rules)
                        val works = androidx.work.WorkManager.getInstance(context)
                            .getWorkInfosByTag(org.fossify.messages.forwarding.MultiChannelForwardWorker::class.java.name).get()
                        val queueReport = "实际待调度：${works.count { it.state == androidx.work.WorkInfo.State.ENQUEUED }}；运行：${works.count { it.state == androidx.work.WorkInfo.State.RUNNING }}\n网络恢复后由原WorkManager约束调度；待调度也可能是延迟或重试。"
                        val auditReport = issues.joinToString("\n").ifBlank { "未发现本次检查范围内的问题" }

                        val attempts = dao.getRecentAttempts(10)
                        val attemptReport = attempts.joinToString("\n") { attempt ->
                            "尝试 ${attempt.attemptNumber}：${attempt.state}；HTTP ${attempt.httpStatus ?: "未记录"}；耗时 " +
                                if (attempt.requestStartedAt != null && attempt.requestFinishedAt != null)
                                    "${(attempt.requestFinishedAt - attempt.requestStartedAt).coerceAtLeast(0)} ms" else "未记录"
                        }
                        val root = context.getSharedPreferences("root_maintenance", android.content.Context.MODE_PRIVATE)
                        val checkedAt = root.getLong("checkedAt", 0)
                        val rootReport = "Root 最近诊断：" + if (checkedAt > 0) DateFormat.getDateTimeInstance().format(Date(checkedAt)) else "暂无"
                        val operations = dao.getRecentOperations()
                        val operationReports = mutableListOf<String>()
                        for (op in operations) {
                            val steps = dao.getSteps(op.operationId)
                            val states = steps.joinToString("\n") { "${it.stepType}：${it.status}" }
                            operationReports.add("${DateFormat.getDateTimeInstance().format(Date(op.createdAt))} · ${op.direction}\n" +
                                "Provider关联：${if (op.providerMessageId != null) "已观察" else "未观察"}\n" + states.ifBlank { "尚无步骤记录" })
                        }
                        val chainReport = if (operations.isEmpty()) "暂无影子记录，请检查影子记录开关。"
                        else operationReports.joinToString("\n\n")
                        listOf(DiagnosticPanel("配置体检", auditReport, if (issues.isEmpty()) "检查范围内正常" else "发现${issues.size}项", issues.isNotEmpty()),
                            DiagnosticPanel("待发队列", queueReport, "待调度${works.count { it.state == androidx.work.WorkInfo.State.ENQUEUED }}"),
                            DiagnosticPanel("短信链路记录", chainReport, if (operations.isEmpty()) "暂无记录" else "${operations.size}条"),
                            DiagnosticPanel("最近通道请求", attemptReport.ifBlank { "暂无记录" }, "${attempts.size}次尝试"),
                            DiagnosticPanel("Root 守护", rootReport + "\n守护：" + org.fossify.messages.security.root.RootWatchdog.observedStatus(context), "实验观察"))
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { report = listOf(DiagnosticPanel("读取失败", "读取诊断记录失败，请稍后重试。", "请重试", true)) }
                finally { checkedAt = System.currentTimeMillis(); loading = false; showDetails = true }
            }
        }) { Text(if (loading) "读取中…" else "检查 / 刷新") }
        androidx.compose.material3.TextButton(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "收起诊断详情" else "展开诊断详情与备用通道")
        }
        }
        if (showDetails) {
        report.forEach { panel -> OperationsSection(panel.title) {
            val title = panel.title
            val body = panel.body
            DiagnosticBadge(panel.status, panel.warning)
            var expanded by remember(title) { mutableStateOf(false) }
            Text(body, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (body.length > 160 || body.count { it == '\n' } > 4) {
                androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开详情") }
            }
            if (panel.warning) androidx.compose.material3.TextButton(onClick = {
                context.startActivity(android.content.Intent(context, org.fossify.messages.activities.ForwardingChannelsActivity::class.java))
            }) { Text("检查通道配置") }
        } }
        OperationsSection("备用通道") {
        Text("仅主通道发送前不可用时切换")
        instances.take(20).forEach { item ->
            OutlinedButton(onClick = { fallbackPrimary = item.id }) {
                Text(item.name + " → " + (instances.firstOrNull { it.id == fallbackConfig.target(item.id) }?.name ?: "未设置"))
            }
        }
        if (instances.isEmpty()) Text("暂无通道实例")
        }
        OperationsSection("后台连接健康") {
        sources.forEach { source ->
            DiagnosticBadge(source.connectionState.label, source.connectionState == org.fossify.messages.remote.repository.RemoteSourceConnectionState.ERROR)

            Text("${source.type.label}：${if (!source.enabled) "已停用" else source.connectionState.label}；最近消息：" +
                if (source.lastMessageAt > 0) DateFormat.getDateTimeInstance().format(Date(source.lastMessageAt)) else "暂无")
        }
        if (sources.isEmpty()) Text("暂无远程来源")
        androidx.compose.material3.TextButton(onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")))
        }) { Text("打开应用权限与后台设置") }
        }
        }

    }
}

@Composable
private fun DiagnosticBadge(label: String, warning: Boolean = false) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = if (warning) colors.errorContainer else colors.secondaryContainer) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = if (warning) colors.onErrorContainer else colors.onSecondaryContainer)
    }
}
