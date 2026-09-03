package org.fossify.messages.helpers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.forwarding.ForwardingMessageFormatter
import org.fossify.messages.forwarding.HeartbeatConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.TemplateDataRetriever
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = HeartbeatConfig(applicationContext)
        if (!config.enabled) return@withContext Result.success()

        val multiConfig = MultiForwardConfig(applicationContext)
        val channels = multiConfig.enabledChannelIds()
        if (channels.isEmpty()) return@withContext Result.success()

        val now = System.currentTimeMillis()
        config.lastReportTime = now

        val reportBody = buildReport(applicationContext, multiConfig, now)
        val uniqueId = "heartbeat-$now"

        channels.forEach { target ->
            MultiChannelForwardWorker.enqueueSingle(
                context = applicationContext,
                sender = "设备心跳",
                body = reportBody,
                receivedAt = now,
                subscriptionId = -1,
                uniqueId = uniqueId,
                targetChannel = target,
                allowedChannels = setOf(target),
                isTest = false
            )
        }
        Log.i(TAG, "Heartbeat report enqueued to ${channels.size} channels")
        Result.success()
    }

    private fun buildReport(context: Context, multiConfig: MultiForwardConfig, now: Long): String {
        val timeFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
        val batteryInfo = TemplateDataRetriever.getBatteryInfo(context)
        val networkInfo = getNetworkStatus(context)
        val uptimeInfo = getUptimeString()
        val deviceName = TemplateDataRetriever.getDeviceName()

        val sim1Desc = multiConfig.simOneLabel.ifBlank { "SIM 1" }
        val sim2Desc = multiConfig.simTwoLabel.ifBlank { "SIM 2" }

        return buildString {
            appendLine("🟢 【设备状态与心跳保活】")
            appendLine("📱 设备机型：$deviceName")
            appendLine("🔋 电池状态：$batteryInfo")
            appendLine("📶 网络环境：$networkInfo")
            appendLine("💳 卡槽一：$sim1Desc")
            appendLine("💳 卡槽二：$sim2Desc")
            appendLine("⏱️ 运行时间：$uptimeInfo")
            appendLine("🕒 报活时间：$timeFormatted")
        }.trim()
    }

    private fun getNetworkStatus(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "未知网络"
        val activeNetwork = cm.activeNetwork ?: return "无网络连接"
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return "无网络连接"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi (已连接)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据 (已连接)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网 (已连接)"
            else -> "已连接"
        }
    }.getOrDefault("网络正常")

    private fun getUptimeString(): String {
        val uptimeMillis = SystemClock.elapsedRealtime()
        val days = TimeUnit.MILLISECONDS.toDays(uptimeMillis)
        val hours = TimeUnit.MILLISECONDS.toHours(uptimeMillis) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMillis) % 60
        return if (days > 0) "${days}天 ${hours}小时 ${minutes}分" else "${hours}小时 ${minutes}分"
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val WORK_NAME = "heartbeat_periodic_work"

        fun sync(context: Context) {
            val config = HeartbeatConfig(context)
            val workManager = WorkManager.getInstance(context)

            if (!config.enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val interval = config.intervalHours.coerceIn(1, 48).toLong()
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(interval, TimeUnit.HOURS)
                .setInitialDelay(interval, TimeUnit.HOURS)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
