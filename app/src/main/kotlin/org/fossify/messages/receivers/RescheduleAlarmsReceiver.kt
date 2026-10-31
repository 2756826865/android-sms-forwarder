package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.rescheduleAllScheduledMessages
import org.fossify.messages.messaging.SmsRecoveryWorker
import org.fossify.messages.helpers.HeartbeatWorker
import org.fossify.messages.helpers.LowBatteryCheckWorker
import org.fossify.messages.services.SmsKeepAliveService
import org.fossify.messages.helpers.RecoverySteps

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
                val steps = RecoverySteps { name, failure ->
                    if (failure == null) Log.i("RecoveryRegistration", "$name registered")
                    else Log.w("RecoveryRegistration", "$name failed: ${failure.javaClass.simpleName}")
                }
                steps.run("scheduled_messages") { context.rescheduleAllScheduledMessages() }
                steps.run("heartbeat") { HeartbeatWorker.sync(context) }
                steps.run("low_battery") { LowBatteryCheckWorker.sync(context) }
                steps.run("periodic_sms_recovery") { SmsRecoveryWorker.schedule(context) }
                steps.run("root_maintenance") { org.fossify.messages.security.root.RootMaintenanceWorker.sync(context) }
                steps.run("immediate_sms_recovery") { SmsRecoveryWorker.enqueueFullResync(context) }
                steps.run("keep_alive") { SmsKeepAliveService.ensureStarted(context) }
                steps.run("remote_sources") {
                    org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager.getInstance(context).sync()
                }
                steps.run("notification_listener") {
                    org.fossify.messages.services.NotificationForwardListenerService.rebindService(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
