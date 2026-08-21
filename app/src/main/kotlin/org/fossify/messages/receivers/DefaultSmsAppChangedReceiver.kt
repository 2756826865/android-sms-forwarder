package org.fossify.messages.receivers

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.fossify.messages.R
import org.fossify.messages.activities.DeviceCompatibilityActivity
import org.fossify.messages.services.SmsKeepAliveService

/** Warns the user when an OEM replaces this app as the default SMS handler. */
class DefaultSmsAppChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) return

        val isDefault = intent.getBooleanExtra(
            Telephony.Sms.Intents.EXTRA_IS_DEFAULT_SMS_APP,
            org.fossify.messages.helpers.DeviceCompatHelper.isDefaultSmsApp(context),
        )
        if (isDefault) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            SmsKeepAliveService.ensureStarted(context)
        } else {
            SmsKeepAliveService.stop(context)
            showDefaultSmsLostNotification(context)
        }
    }

    private fun showDefaultSmsLostNotification(context: Context) {
        createChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openCompatibility = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, DeviceCompatibilityActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(context.getString(R.string.default_sms_lost_notification_title))
            .setContentText(context.getString(R.string.default_sms_lost_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.default_sms_lost_notification_text))
            )
            .setContentIntent(openCompatibility)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.default_sms_alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.default_sms_alert_channel_description)
            }
        )
    }

    private companion object {
        const val CHANNEL_ID = "default_sms_status_alerts"
        const val NOTIFICATION_ID = 19082
    }
}
