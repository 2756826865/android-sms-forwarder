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
import org.fossify.messages.activities.WebSocketRemoteControlSettingsActivity
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.remote.WebSocketRemoteClient

class WebSocketRemoteControlService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var client: WebSocketRemoteClient? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = MultiForwardConfig(applicationContext)
        if (!config.websocketRemoteControlEnabled) {
            stopClient()
            stopSelf()
            return START_NOT_STICKY
        }
        val url = config.websocketRemoteUrl()
        if (url.isBlank()) {
            config.appendWebSocketRemoteLog("缺少 WebSocket 服务器 URL")
            stopClient()
            stopSelf()
            return START_NOT_STICKY
        }
        stopClient()
        client = WebSocketRemoteClient(
            context = applicationContext,
            onStatus = { status ->
                MultiForwardConfig(applicationContext).appendWebSocketRemoteLog(status)
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
            .setContentTitle(getString(R.string.websocket_remote_title))
            .setContentText(status)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, WebSocketRemoteControlSettingsActivity::class.java),
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
                    getString(R.string.websocket_remote_title),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        updateNotification("正在连接 WebSocket…")
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
        private const val CHANNEL_ID = "ws_remote_control"
        private const val NOTIFICATION_ID = 19088

        fun ensureStarted(context: Context) {
            val config = MultiForwardConfig(context)
            if (!config.websocketRemoteControlEnabled) {
                context.stopService(Intent(context, WebSocketRemoteControlService::class.java))
                return
            }
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WebSocketRemoteControlService::class.java),
                )
            }.onFailure { error ->
                config.appendWebSocketRemoteLog("启动失败：${error.message ?: error.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebSocketRemoteControlService::class.java))
        }
    }
}