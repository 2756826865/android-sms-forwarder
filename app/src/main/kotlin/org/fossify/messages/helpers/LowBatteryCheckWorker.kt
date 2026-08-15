package org.fossify.messages.helpers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.fossify.messages.R
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.forwarding.PushPlusWorker
import java.util.concurrent.TimeUnit

class LowBatteryCheckWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val config = Config(appContext)

        if (!config.enableLowBatteryReminder) {
            return Result.success()
        }

        val selectedChannels = config.lowBatteryChannels
        val multiConfig = MultiForwardConfig(appContext)
        val enabledMultiChannels = multiConfig.enabledChannelIds().intersect(selectedChannels)
        val pushPlusEnabled = ForwardingChannels.PUSHPLUS in selectedChannels &&
            PushPlusConfig(appContext).enabled

        if (enabledMultiChannels.isEmpty() && !pushPlusEnabled) {
            return Result.success()
        }

        val batteryLevel = getBatteryLevel()
        if (batteryLevel == -1) {
            return Result.success()
        }

        val threshold = config.lowBatteryThreshold
        val lastNotifiedLevel = config.lowBatteryLastNotifiedLevel

        if (batteryLevel <= threshold && lastNotifiedLevel == -1) {
            val title = appContext.getString(R.string.low_battery_alert_title)
            val body = appContext.getString(R.string.low_battery_alert_body, threshold, batteryLevel)
            val content = "$title\n$body"

            try {
                val uniqueId = "low-battery-${System.currentTimeMillis()}"
                val now = System.currentTimeMillis()
                if (enabledMultiChannels.isNotEmpty()) {
                    MultiChannelForwardWorker.enqueue(
                        context = appContext,
                        sender = appContext.getString(R.string.low_battery_system_sender),
                        body = content,
                        receivedAt = now,
                        subscriptionId = -1,
                        uniqueId = uniqueId,
                        allowedChannels = enabledMultiChannels,
                    )
                }
                if (pushPlusEnabled) {
                    PushPlusWorker.enqueue(
                        context = appContext,
                        sender = appContext.getString(R.string.low_battery_system_sender),
                        body = content,
                        receivedAt = now,
                        subscriptionId = -1,
                        uniqueId = uniqueId,
                    )
                }
                config.lowBatteryLastNotifiedLevel = batteryLevel
            } catch (_: Exception) {
                return Result.retry()
            }
        } else if (batteryLevel > threshold) {
            config.lowBatteryLastNotifiedLevel = -1
        }

        return Result.success()
    }

    private fun getBatteryLevel(): Int {
        val batteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        if (level == -1 || scale == -1) {
            return -1
        }

        return (level * 100) / scale
    }

    companion object {
        const val UNIQUE_PERIODIC = "low-battery-check-periodic"

        fun sync(context: Context) {
            val config = Config(context)
            if (config.enableLowBatteryReminder && config.lowBatteryChannels.isNotEmpty()) {
                schedule(context)
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
            }
        }

        private fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LowBatteryCheckWorker>(
                15, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
