package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.rescheduleAllScheduledMessages
import org.fossify.messages.messaging.SmsRecoveryWorker
import org.fossify.messages.helpers.HeartbeatWorker
import org.fossify.messages.helpers.LowBatteryCheckWorker
import org.fossify.messages.services.SmsKeepAliveService

/**
 * Reschedules alarms after boot/package updates.
 */
class RescheduleAlarmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Explicit broadcasts bypass intent filters; only handle the system events we register.
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        val pendingResult = goAsync()
        ensureBackgroundThread {
            try {
                context.rescheduleAllScheduledMessages()
                HeartbeatWorker.sync(context)
                LowBatteryCheckWorker.sync(context)
                SmsRecoveryWorker.schedule(context)
                SmsRecoveryWorker.enqueueFullResync(context)
                SmsKeepAliveService.ensureStarted(context)
                org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager.getInstance(context).sync()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
