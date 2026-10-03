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
    var loading by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("点击检查最近10条影子记录；记录缺失不代表业务失败。") }
    val sources by remember { org.fossify.messages.remote.repository.RemoteSourceRepository.getInstance(context) }.sourcesFlow.collectAsState()
    val sync by org.fossify.messages.helpers.SmsSyncProgress.state.collectAsState()
    Column(Modifier.padding(14.dp)) {
        Text("短信同步：${if (sync.running) "进行中" else "未运行"} · ${sync.completed}/${sync.total} 个会话 · ${sync.failed} 个失败")
        Text("短信链路诊断")
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
                        val auditReport = "配置体检\n" + issues.joinToString("\n").ifBlank { "未发现本次检查范围内的问题" }

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
                        auditReport + "\n\n待发队列\n" + queueReport + "\n\n" + chainReport + "\n\n最近通道请求尝试（独立记录）\n" + attemptReport.ifBlank { "暂无记录" } + "\n\n" + rootReport + "\n守护：" + org.fossify.messages.security.root.RootWatchdog.observedStatus(context)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { report = "读取诊断记录失败，请稍后重试。" }
                finally { loading = false }
            }
        }) { Text(if (loading) "读取中…" else "检查 / 刷新") }
        Text(report)
        Text("备用通道：仅主通道发送前不可用时切换")
        instances.take(20).forEach { item ->
            OutlinedButton(onClick = { fallbackPrimary = item.id }) {
                Text(item.name + " → " + (instances.firstOrNull { it.id == fallbackConfig.target(item.id) }?.name ?: "未设置"))
            }
        }
        Text("后台连接健康（应用记录）")
        sources.forEach { source ->
            Text("${source.type.label}：${if (!source.enabled) "已停用" else source.connectionState.label}；最近消息：" +
                if (source.lastMessageAt > 0) DateFormat.getDateTimeInstance().format(Date(source.lastMessageAt)) else "暂无")
        }
        if (sources.isEmpty()) Text("暂无远程来源")

    }
}
