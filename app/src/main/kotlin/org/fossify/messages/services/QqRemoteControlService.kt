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
import org.fossify.messages.activities.QqRemoteControlSettingsActivity
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.remote.QqRemoteClient

class QqRemoteControlService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var client: QqRemoteClient? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = MultiForwardConfig(applicationContext)
        if (!config.qqRemoteControlEnabled) {
            stopClient()
            stopSelf()
            return START_NOT_STICKY
        }
        val url = config.qqRemoteWsUrl()
        if (url.isBlank()) {
            config.appendQqRemoteLog("缺少 OneBot 11 WebSocket URL")
            stopClient()
            stopSelf()
            return START_NOT_STICKY
        }
        stopClient()
        client = QqRemoteClient(
            context = applicationContext,
            onStatus = { status ->
                MultiForwardConfig(applicationContext).appendQqRemoteLog(status)
                mainHandler.post { updateNotification(status) }
            },
        ).also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopClient()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopClient() {
        client?.stop()
        client = null
    }

    private fun updateNotification(status: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.qq_remote_title))
            .setContentText(status)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, QqRemoteControlSettingsActivity::class.java),
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
                    getString(R.string.qq_remote_title),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        updateNotification("正在连接 QQ (OneBot 11)…")
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
        private const val CHANNEL_ID = "qq_remote_control"
        private const val NOTIFICATION_ID = 19089

        fun ensureStarted(context: Context) {
            val config = MultiForwardConfig(context)
            if (!config.qqRemoteControlEnabled) {
                context.stopService(Intent(context, QqRemoteControlService::class.java))
                return
            }
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, QqRemoteControlService::class.java),
                )
            }.onFailure { error ->
                config.appendQqRemoteLog("启动失败：${error.message ?: error.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QqRemoteControlService::class.java))
        }
    }
}