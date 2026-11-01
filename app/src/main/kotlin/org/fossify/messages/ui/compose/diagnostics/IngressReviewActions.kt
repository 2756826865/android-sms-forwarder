package org.fossify.messages.ui.compose.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.messages.messaging.SmsIngressReview

/** Explicit review never silently inserts SMS or chooses new forwarding destinations. */
@Composable
internal fun IngressReviewActions() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(emptyList<SmsIngressReview.Item>()) }
    var selected by remember { mutableStateOf<SmsIngressReview.Item?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val load: (Boolean) -> Unit = { reconcile ->
        loading = true
        scope.launch {
            try {
                items =
                    withContext(Dispatchers.IO) {
                        val review = SmsIngressReview(context)
                        if (reconcile) review.reconcile()
                        review.items()
                    }
                status = if (items.isEmpty()) "当前没有待核对记录" else "显示最早的 ${items.size} 条待核对记录"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "读取失败，请稍后重试"
            } finally {
                loading = false
            }
        }
    }
    Column {
        Text("异常写入只查询已有短信，不重新插入。配置变化的事件不会自动改投新目标。")
        Row {
            TextButton(enabled = !loading, onClick = { load(false) }) { Text("刷新待核对") }
            TextButton(enabled = !loading, onClick = { load(true) }) { Text("只读核对写入") }
        }
        if (status.isNotEmpty()) Text(status)
        items.forEach { item ->
            val reason =
                when (item.reason) {
                    "provider_result_uncertain" -> "写入结果不确定"
                    "configuration_changed" -> "转发配置已变化"
                    "notification_pending" -> "通知发布失败"
                    else -> "恢复数据暂不可读"
                }
            TextButton(enabled = !loading, onClick = { selected = item }) {
                Text(
                    "${DateFormat.getDateTimeInstance().format(Date(item.receivedAt))} · $reason · ${item.key.take(8)}"
                )
            }
        }
    }
    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("结束这条恢复记录？") },
            text = { Text("结束后不再自动恢复这条记录；不会重新发送，也不会删除系统短信、转发历史或已登记任务。无法确认结果时可以保留待核对。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        selected = null
                        loading = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    SmsIngressReview(context).dismiss(item)
                                }
                                load(false)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                status = "结束失败，请重试"
                            } finally {
                                loading = false
                            }
                        }
                    }
                ) {
                    Text("结束本条恢复")
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("保留待核对") } },
        )
    }
}
