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
import org.fossify.messages.activities.FeishuRemoteControlSettingsActivity
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.helpers.RemoteCommandRepository
import org.fossify.messages.messaging.SimSendResolver
import org.fossify.messages.models.RemoteCommandContext
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.models.RemoteCommandType
import org.fossify.messages.remote.FeishuRemoteCommand
import org.fossify.messages.remote.FeishuStreamClient
import org.fossify.messages.remote.RemoteSmsCommandWorker
import org.fossify.messages.remote.SOURCE_FEISHU
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

class FeishuRemoteControlService : Service() {
    private var streamClient: FeishuStreamClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = MultiForwardConfig(applicationContext)
        if (!config.feishuRemoteControlEnabled) {
            stopStream()
            stopSelf()
            return START_NOT_STICKY
        }
        val appId = config.feishuRemoteAppId()
        val appSecret = config.feishuRemoteAppSecret()
        if (appId.isBlank() || appSecret.isBlank()) {
            config.appendFeishuRemoteLog("缺少 App ID 或 App Secret")
            stopStream()
            stopSelf()
            return START_NOT_STICKY
        }
        stopStream()
        streamClient = FeishuStreamClient(
            appId = appId,
            appSecret = appSecret,
            customPrefix = config.feishuRemoteCustomPrefix(),
            onCommand = ::handleCommand,
            onStatus = { status ->
                MultiForwardConfig(applicationContext).appendFeishuRemoteLog(status)
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

    private fun handleCommand(event: FeishuRemoteCommand) {
        val command = event.command
        val config = MultiForwardConfig(applicationContext)
        val messageKey = event.messageId.takeIf(String::isNotBlank)
            ?: "feishu-${command.targetNumber}-${command.content.hashCode()}"
        val sendMode = command.effectiveSendMode(config.feishuRemoteSendSimMode)

        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.FEISHU,
            sourceMessageKey = messageKey,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = command.targetNumber,
            rawPayload = command.content,
            requestedSimMode = sendMode,
            rawRequester = "feishu-stream",
            receivedAt = System.currentTimeMillis(),
        )

        val claimResult = runBlocking {
            RemoteCommandRepository.claimOrGetDuplicate(applicationContext, cmdContext)
        }

        if (claimResult is RemoteCommandRepository.ClaimResult.Duplicate) {
            config.appendFeishuRemoteLog("抑制重复指令 -> ${command.targetNumber}")
            return
        }

        val commandId = (claimResult as? RemoteCommandRepository.ClaimResult.NewCommand)?.commandId.orEmpty()
        val fingerprint = event.messageId.takeIf(String::isNotBlank)
            ?.let { "feishu-${sha256(it)}" }
            ?: "feishu-${command.targetNumber}-${command.content.hashCode()}"
        val simSuffix = " · ${SimSendResolver.describeForLog(applicationContext, null, sendMode)}"
        config.appendFeishuRemoteLog("收到指令 -> ${command.targetNumber}$simSuffix")

        val rulesConfig = ForwardingRulesConfig(applicationContext)
        if (rulesConfig.affectsRemoteCommands() && rulesConfig.rules.any { it.enabled }) {
            val decision = ForwardingRuleEngine(rulesConfig.rules).evaluate(
                sender = SOURCE_FEISHU,
                body = "${command.targetNumber} ${command.content}",
                subscriptionId = -1,
                channelCandidates = emptySet(),
                simSlotIndex = null,
            )
            if (decision.matchedRules.isEmpty()) {
                if (commandId.isNotBlank()) {
                    RemoteCommandRepository.recordAuthorization(applicationContext, commandId, authorized = false, reason = "RULE_BLOCKED")
                }
                config.appendFeishuRemoteLog("规则阻止执行 -> ${command.targetNumber}$simSuffix")
                return
            }
        }

        if (commandId.isNotBlank()) {
            RemoteCommandRepository.recordAuthorization(applicationContext, commandId, authorized = true, reason = "FEISHU_AUTHORIZED")
        }

        RemoteSmsCommandWorker.enqueue(
            context = applicationContext,
            target = command.targetNumber,
            content = command.content,
            subId = -1,
            uniqueId = fingerprint,
            sendMode = sendMode,
            source = SOURCE_FEISHU,
            commandId = commandId,
        )
    }

    private fun stopStream() {
        streamClient?.stop()
        streamClient = null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun updateNotification(status: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.feishu_remote_title))
            .setContentText(status)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, FeishuRemoteControlSettingsActivity::class.java),
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
                    getString(R.string.feishu_remote_title),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        updateNotification("正在连接飞书…")
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
        private const val CHANNEL_ID = "feishu_remote_control"
        private const val NOTIFICATION_ID = 19084

        fun ensureStarted(context: Context) {
            val config = MultiForwardConfig(context)
            if (!config.feishuRemoteControlEnabled) {
                context.stopService(Intent(context, FeishuRemoteControlService::class.java))
                return
            }
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FeishuRemoteControlService::class.java),
                )
            }.onFailure { error ->
                config.appendFeishuRemoteLog("启动失败：${error.message ?: error.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FeishuRemoteControlService::class.java))
        }
    }
}