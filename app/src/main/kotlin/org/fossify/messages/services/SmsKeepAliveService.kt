package org.fossify.messages.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity

/**
 * Keeps the default-SMS process available on OEM builds that aggressively stop background apps.
 * Incoming SMS delivery still comes from the platform receiver; this service is only a reliability
 * aid and never polls or holds a permanent wake lock.
 */
class SmsKeepAliveService : Service() {
    private var startupFailed = false

    // Framework/OEM boundary: retain durable state and report unclassified platform failures.
    @Suppress("TooGenericExceptionCaught")
    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
            running = true
            record(this, "foreground", "")
        } catch (error: Exception) {
            running = false
            startupFailed = true
            record(this, "failed", error.javaClass.simpleName)
            Log.w("SmsKeepAlive", "foreground failed: ${error.javaClass.simpleName}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        if (!startupFailed) record(this, "stopped", "")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A sticky restart can arrive after the switch or default-SMS role changed.
        if (!shouldRun(applicationContext) || !running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.keep_alive_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "sms_background_service"
        private const val NOTIFICATION_ID = 19081

        private fun shouldRun(context: Context): Boolean =
            org.fossify.messages.forwarding.MultiForwardConfig(context).keepAliveServiceEnabled

        val runtimeVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        @Volatile private var running = false

        enum class StartResult {
            DISABLED,
            REQUESTED,
            FAILED,
        }

        private fun record(context: Context, state: String, reason: String) {
            runCatching {
                context
                    .getSharedPreferences("keep_alive_runtime", Context.MODE_PRIVATE)
                    .edit()
                    .putString("state", state)
                    .putString("reason", reason)
                    .putLong("at", System.currentTimeMillis())
                    .putInt("pid", android.os.Process.myPid())
                    .apply()
            }
            runtimeVersion.value = runtimeVersion.value + 1
        }

        fun observedStatus(context: Context): String {
            val p = context.getSharedPreferences("keep_alive_runtime", Context.MODE_PRIVATE)
            val configured = if (shouldRun(context)) "开启" else "关闭"
            val actual =
                if (running) "已完成前台化"
                else if (p.getString("state", "") == "failed") "启动失败：${p.getString("reason", "")}"
                else "未运行或待确认"
            return "用户设置：$configured；实际：$actual；最近变化：${p.getLong("at",0L)}"
        }

        // Framework/OEM boundary: retain durable state and report unclassified platform failures.
        @Suppress("TooGenericExceptionCaught")
        fun ensureStarted(context: Context): StartResult {
            if (!shouldRun(context)) {
                stop(context)
                return StartResult.DISABLED
            }
            return try {
                if (!running) record(context, "requested", "")
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, SmsKeepAliveService::class.java),
                )
                StartResult.REQUESTED
            } catch (error: Exception) {
                record(context, "failed", error.javaClass.simpleName)
                Log.w("SmsKeepAlive", "start rejected: ${error.javaClass.simpleName}")
                StartResult.FAILED
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, SmsKeepAliveService::class.java)
            )
        }
    }
}
