package org.fossify.messages.messaging

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import org.fossify.messages.helpers.SCHEDULED_MESSAGE_ID
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.models.Message
import org.fossify.messages.receivers.ScheduledMessageReceiver

/**
 * All things related to scheduled messages are here.
 */

private fun scheduleRequestCode(messageId: Long): Int = (messageId xor (messageId ushr 32)).toInt()

fun Context.getScheduleSendPendingIntent(message: Message): PendingIntent {
    val intent = Intent(this, ScheduledMessageReceiver::class.java)
    intent.putExtra(THREAD_ID, message.threadId)
    intent.putExtra(SCHEDULED_MESSAGE_ID, message.id)

    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(this, scheduleRequestCode(message.id), intent, flags)
}

fun Context.scheduleMessage(message: Message) {
    val pendingIntent = getScheduleSendPendingIntent(message)
    val triggerAtMillis = message.millis()

    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val canUseExactAlarm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    if (canUseExactAlarm) {
        try {
            AlarmManagerCompat.setExactAndAllowWhileIdle(
                alarmManager,
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            return
        } catch (_: SecurityException) {
            // 权限可能在检查后被系统或用户撤销，继续使用非精确闹钟，避免整条恢复任务中断。
        }
    }
    AlarmManagerCompat.setAndAllowWhileIdle(
        alarmManager,
        AlarmManager.RTC_WAKEUP,
        triggerAtMillis,
        pendingIntent
    )
}

fun Context.cancelScheduleSendPendingIntent(messageId: Long) {
    val intent = Intent(this, ScheduledMessageReceiver::class.java)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    // Cancel both the collision-resistant requestCode and legacy messageId.toInt() for pre-existing alarms.
    val codes = setOf(scheduleRequestCode(messageId), messageId.toInt())
    for (code in codes) {
        val pi = PendingIntent.getBroadcast(this, code, intent, flags)
        alarmManager?.cancel(pi)
        pi.cancel()
    }
}
