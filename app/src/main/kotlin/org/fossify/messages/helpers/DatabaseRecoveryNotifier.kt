package org.fossify.messages.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.fossify.commons.extensions.notificationManager
import org.fossify.messages.R
import org.fossify.messages.receivers.DatabaseRecoveryDismissReceiver

/**
 * 本地库进入恢复模式时的一次性告知。
 *
 * 复用短信通知渠道 [NOTIFICATION_CHANNEL_ID]，不为此单独建渠道；
 * 通知只发一次（[DatabaseHealth.hasNotifiedRecovery]），用户点「知道了」后
 * 由 [DatabaseRecoveryDismissReceiver] 清除标志并撤下通知。
 */
object DatabaseRecoveryNotifier {

    const val NOTIFICATION_ID = 2000_0016

    fun notifyIfNeeded(context: Context) {
        if (!DatabaseHealth.isRecoveryMode(context)) return
        if (DatabaseHealth.hasNotifiedRecovery(context)) return

        ensureChannel(context)

        val dismissIntent = Intent(context, DatabaseRecoveryDismissReceiver::class.java).apply {
            action = DatabaseRecoveryDismissReceiver.ACTION_DISMISS
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 内存降级是比空库重启更严重的一级：本地写入不会落盘，提示必须更醒目（常驻 + 最高优先级）
        val degraded = DatabaseHealth.isDegradedToInMemory(context)
        val title = context.getString(
            if (degraded) R.string.database_degraded_in_memory_title
            else R.string.database_recovery_mode_title
        )
        val text = context.getString(
            if (degraded) R.string.database_degraded_in_memory_text
            else R.string.database_recovery_mode_text
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_messenger)
            .setPriority(if (degraded) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOngoing(degraded)
            .setAutoCancel(false)
            .addAction(
                0,
                context.getString(R.string.database_recovery_action_known),
                dismissPendingIntent
            )
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .build()

        runCatching {
            context.notificationManager.notify(NOTIFICATION_ID, notification)
        }.onFailure {
            // 通知权限未授予 / 渠道被禁用时静默失败，不能因此影响启动流程
            android.util.Log.w("DatabaseRecovery", "恢复模式提示通知发送失败", it)
        }
        DatabaseHealth.markRecoveryNotified(context)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.notificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.channel_received_sms),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }
}
