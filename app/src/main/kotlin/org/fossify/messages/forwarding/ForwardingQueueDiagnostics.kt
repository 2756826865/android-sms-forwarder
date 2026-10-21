package org.fossify.messages.forwarding

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** User-triggered snapshots only. No polling, network probe, cancellation or resubmission. */
internal object ForwardingQueueDiagnostics {
    private data class NetworkSnapshot(val ready: Boolean?, val text: String)

    fun forRecord(context: Context, record: ForwardingHistoryRecord): String {
        val manager = WorkManager.getInstance(context.applicationContext)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(1L)
        val tagged = manager.getWorkInfos(WorkQuery.Builder.fromTags(
            listOf("forwarding-history:${record.recordId}")).build())
            .get(remaining(), TimeUnit.NANOSECONDS)
        // Older releases did not tag history IDs. Look up their original unique work name.
        val works = if (tagged.isNotEmpty()) tagged else manager.getWorkInfosForUniqueWork(
            ForwardingQueuePolicy.uniqueName(record.workId, record.channel))
            .get(remaining(), TimeUnit.NANOSECONDS)
        val network = networkSnapshot(context)
        return buildString {
            appendLine(network.text)
            appendLine(backgroundStatus(context))
            appendLine("日志状态：${record.status}；记录尝试次数：${record.attempts}")
            val history = ForwardingHistoryStore(context)
            if (history.hasPendingManualSubmission(record.recordId)) appendLine("重试请求已保存；等待按原任务 ID 恢复提交")
            val observed = history.queueObservation(record.recordId)
            if (observed.isNotBlank()) appendLine("队列核对：$observed")
            if (works.isEmpty()) append(ForwardingQueuePolicy.describe(null, 0, 0, 0, false, null))
            else works.takeLast(5).forEach { appendLine(); append(describe(it, network.ready)) }
            append("\n\n这是本次状态快照；未通过系统网络验证不等于通道不可访问。此检查不会重发短信。")
        }
    }

    fun summary(context: Context, works: List<WorkInfo>): String {
        val network = networkSnapshot(context)
        return buildString {
            appendLine("实际待调度：${works.count { it.state == WorkInfo.State.ENQUEUED }}；运行：${works.count { it.state == WorkInfo.State.RUNNING }}")
            appendLine(network.text)
            appendLine(backgroundStatus(context))
            works.filter { !it.state.isFinished }.take(10).forEach {
                appendLine(describe(it, network.ready))
            }
            append("状态为本次快照；具体通道可在转发日志中点击“查看排队原因”。")
        }
    }

    private fun describe(info: WorkInfo, ready: Boolean?): String = buildString {
        append(ForwardingQueuePolicy.describe(info.state.name, info.runAttemptCount,
            info.nextScheduleTimeMillis, System.currentTimeMillis(),
            info.constraints.requiredNetworkType != NetworkType.NOT_REQUIRED, ready))
        append("\n系统状态：${info.state.name}；启动计数：${info.runAttemptCount}")
        if (info.runAttemptCount > 0) append("；上次停止原因代码：${info.stopReason}")
        if (info.state == WorkInfo.State.ENQUEUED && info.nextScheduleTimeMillis != Long.MAX_VALUE &&
            info.nextScheduleTimeMillis > System.currentTimeMillis()) {
            append("\n最早执行时间：${DateFormat.getDateTimeInstance().format(Date(info.nextScheduleTimeMillis))}（系统可能延后）")
        }
    }

    private fun networkSnapshot(context: Context): NetworkSnapshot = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null) NetworkSnapshot(false, "当前没有活动网络")
        else {
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                else -> "其他网络"
            }
            val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            NetworkSnapshot(internet && validated,
                "$kind · 联网能力：${if (internet) "有" else "无"} · 系统验证：${if (validated) "通过" else "未通过"}")
        }
    } catch (_: Exception) { NetworkSnapshot(null, "无法读取网络状态") }

    private fun backgroundStatus(context: Context): String = buildList {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            add(when (cm.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "省流量模式：后台联网受限"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "省流量模式：应用已获豁免"
                else -> "省流量模式：未限制"
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                add("系统后台限制：${if (am.isBackgroundRestricted) "已限制" else "未报告限制"}")
            }
        }.onFailure { add("部分后台限制状态无法读取") }
    }.joinToString("；")
}
