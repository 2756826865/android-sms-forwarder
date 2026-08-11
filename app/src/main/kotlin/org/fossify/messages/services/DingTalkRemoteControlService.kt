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
import org.fossify.messages.activities.DingTalkRemoteControlSettingsActivity
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.messaging.SimSendResolver
import org.fossify.messages.remote.DingTalkStreamClient
import org.fossify.messages.remote.RemoteSmsCommand
import org.fossify.messages.remote.RemoteSmsCommandWorker
import org.fossify.messages.remote.SOURCE_DINGTALK

class DingTalkRemoteControlService : Service() {
    private var streamClient: DingTalkStreamClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = MultiForwardConfig(applicationContext)
        if (!config.dingTalkRemoteControlEnabled) {
            stopStream()
            stopSelf()
            return START_NOT_STICKY
        }
        val clientId = config.dingTalkRemoteClientId()
        val clientSecret = config.dingTalkRemoteClientSecret()
        if (clientId.isBlank() || clientSecret.isBlank()) {
            config.appendDingTalkRemoteLog("缺少 Client ID 或 Client Secret")
            stopStream()
            stopSelf()
            return START_NOT_STICKY
        }
        stopStream()
        streamClient = DingTalkStreamClient(
            clientId = clientId,
            clientSecret = clientSecret,
            onCommand = { command -> handleCommand(command) },
            onStatus = { status ->
                MultiForwardConfig(applicationContext).appendDingTalkRemoteLog(status)
                mainHandler.post { updateNotification(status) }
            },
        ).also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopStream()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleCommand(command: RemoteSmsCommand) {
        val config = MultiForwardConfig(applicationContext)
        val fingerprint = "dingtalk-${command.targetNumber}-${command.content.hashCode()}"
        val sendMode = command.effectiveSendMode(config.dingTalkRemoteSendSimMode)
        val simSuffix = " · ${SimSendResolver.describeForLog(applicationContext, null, sendMode)}"
        config.appendDingTalkRemoteLog("收到指令 -> ${command.targetNumber}$simSuffix")
        RemoteSmsCommandWorker.enqueue(
            context = applicationContext,
            target = command.targetNumber,
            content = command.content,
            subId = -1,
            uniqueId = fingerprint,
            sendMode = sendMode,
            source = SOURCE_DINGTALK,
        )
    }

    private fun stopStream() {
        streamClient?.stop()
        streamClient = null
    }

    private fun updateNotification(status: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.dingtalk_remote_title))
            .setContentText(status)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, DingTalkRemoteControlSettingsActivity::class.java),
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
                    getString(R.string.dingtalk_remote_title),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        updateNotification(getString(R.string.dingtalk_remote_connecting))
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
        private const val CHANNEL_ID = "dingtalk_remote_control"
        private const val NOTIFICATION_ID = 19083

        fun ensureStarted(context: Context) {
            val config = MultiForwardConfig(context)
            if (!config.dingTalkRemoteControlEnabled) {
                context.stopService(Intent(context, DingTalkRemoteControlService::class.java))
                return
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, DingTalkRemoteControlService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DingTalkRemoteControlService::class.java))
        }
    }
}
