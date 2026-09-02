package org.fossify.messages.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.fossify.messages.R
import org.fossify.messages.activities.EmailRemoteControlSettingsActivity
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.remote.EmailRemoteCommandPoller
import java.util.concurrent.atomic.AtomicBoolean

class EmailRemoteControlService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var poller: EmailRemoteCommandPoller? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = MultiForwardConfig(applicationContext)
        if (!config.emailRemoteControlEnabled) {
            stopLoop()
            stopSelf()
            return START_NOT_STICKY
        }
        val host = config.emailRemoteHost()
        val user = config.emailRemoteUser()
        val pass = config.emailRemotePassword()
        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            config.appendEmailRemoteLog("缺少邮箱主机 / 账号 / 授权码")
            stopLoop()
            stopSelf()
            return START_NOT_STICKY
        }
        poller = EmailRemoteCommandPoller(applicationContext)
        startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        stopLoop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLoop() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            while (running.get()) {
                val processed = poller?.pollOnce() ?: 0
                val status = if (processed > 0) "已处理 $processed 条邮件指令" else "邮箱指令监听中 · 正常"
                MultiForwardConfig(applicationContext).appendEmailRemoteLog(status)
                mainHandler.post { updateNotification(status) }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "email-remote"
            isDaemon = true
            start()
        }
    }

    private fun stopLoop() {
        running.set(false)
    }

    private fun updateNotification(status: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.email_remote_title))
            .setContentText(status)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, EmailRemoteControlSettingsActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForegroundCompat(notification)
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.email_remote_title),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        updateNotification("正在连接邮箱…")
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "email_remote_control"
        private const val NOTIFICATION_ID = 19086
        private const val POLL_INTERVAL_MS = 60_000L

        fun ensureStarted(context: Context) {
            val config = MultiForwardConfig(context)
            if (!config.emailRemoteControlEnabled) {
                context.stopService(Intent(context, EmailRemoteControlService::class.java))
                return
            }
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, EmailRemoteControlService::class.java),
                )
            }.onFailure { error ->
                config.appendEmailRemoteLog("启动失败：${error.message ?: error.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmailRemoteControlService::class.java))
        }
    }
}