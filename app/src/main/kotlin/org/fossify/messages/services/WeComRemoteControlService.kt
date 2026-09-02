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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fossify.messages.R
import org.fossify.messages.activities.WeComRemoteControlSettingsActivity
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.helpers.RemoteCommandRepository
import org.fossify.messages.messaging.SimSendResolver
import org.fossify.messages.models.RemoteCommandContext
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.models.RemoteCommandType
import org.fossify.messages.remote.RemoteSmsCommand
import org.fossify.messages.remote.RemoteSmsCommandWorker
import org.fossify.messages.remote.SOURCE_WECOM
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WeComRemoteControlService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = MultiForwardConfig(applicationContext)
        if (!config.weComRemoteControlEnabled) {
            stopLoop()
            stopSelf()
            return START_NOT_STICKY
        }
        val corpId = config.weComRemoteCorpId()
        val agentId = config.weComRemoteAgentId()
        val secret = config.weComRemoteSecret()
        if (corpId.isBlank() || agentId.isBlank() || secret.isBlank()) {
            config.appendWeComRemoteLog("缺少 CorpID / AgentID / Secret")
            stopLoop()
            stopSelf()
            return START_NOT_STICKY
        }
        startLoop(corpId, secret)
        return START_STICKY
    }

    override fun onDestroy() {
        stopLoop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLoop(corpId: String, secret: String) {
        if (!running.compareAndSet(false, true)) return
        Thread {
            while (running.get()) {
                try {
                    val token = fetchAccessToken(corpId, secret)
                    if (token.isNotBlank()) {
                        val status = "已授权企业微信 · 守护运行中"
                        MultiForwardConfig(applicationContext).appendWeComRemoteLog(status)
                        mainHandler.post { updateNotification(status) }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "WeCom polling error", e)
                    val err = "连接状态：${e.message ?: e.javaClass.simpleName}"
                    MultiForwardConfig(applicationContext).appendWeComRemoteLog(err)
                    mainHandler.post { updateNotification(err) }
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "wecom-remote"
            isDaemon = true
            start()
        }
    }

    private fun stopLoop() {
        running.set(false)
    }

    private fun fetchAccessToken(corpId: String, secret: String): String {
        val url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" +
            URLEncoder.encode(corpId, "UTF-8") + "&corpsecret=" + URLEncoder.encode(secret, "UTF-8")
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) return ""
            val json = JSONObject(body)
            return if (json.optInt("errcode", -1) == 0) json.optString("access_token") else ""
        }
    }

    fun processIncomingMessage(user: String, content: String, msgId: String = "") {
        val config = MultiForwardConfig(applicationContext)
        val authUsers = config.weComRemoteAuthorizedUsers().split('\n', ',', ';', '，', '；')
            .map(String::trim).filter(String::isNotBlank)

        if (authUsers.isNotEmpty() && !authUsers.any { it.equals(user, ignoreCase = true) }) {
            config.appendWeComRemoteLog("忽略未授权企业微信用户 [$user]")
            return
        }

        val command = RemoteSmsCommand.parse(content, config.weComRemoteCustomPrefix()) ?: return
        val messageKey = msgId.ifBlank { "wecom-$user-${command.targetNumber}-${command.content.hashCode()}" }
        val sendMode = command.effectiveSendMode(config.weComRemoteSendSimMode)

        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.WECOM,
            sourceMessageKey = messageKey,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = command.targetNumber,
            rawPayload = command.content,
            requestedSimMode = sendMode,
            rawRequester = user,
            receivedAt = System.currentTimeMillis(),
        )

        val claimResult = runBlocking {
            RemoteCommandRepository.claimOrGetDuplicate(applicationContext, cmdContext)
        }

        if (claimResult is RemoteCommandRepository.ClaimResult.Duplicate) {
            config.appendWeComRemoteLog("抑制重复指令 -> ${command.targetNumber}")
            return
        }

        val commandId = (claimResult as? RemoteCommandRepository.ClaimResult.NewCommand)?.commandId.orEmpty()
        val fingerprint = "wecom-${sha256(messageKey)}"
        val simSuffix = " · ${SimSendResolver.describeForLog(applicationContext, null, sendMode)}"
        config.appendWeComRemoteLog("收到指令 -> ${command.targetNumber}$simSuffix (用户: $user)")

        val rulesConfig = ForwardingRulesConfig(applicationContext)
        if (rulesConfig.affectsRemoteCommands() && rulesConfig.rules.any { it.enabled }) {
            val decision = ForwardingRuleEngine(rulesConfig.rules).evaluate(
                sender = SOURCE_WECOM,
                body = "${command.targetNumber} ${command.content}",
                subscriptionId = -1,
                channelCandidates = emptySet(),
                simSlotIndex = null,
            )
            if (decision.matchedRules.isEmpty()) {
                if (commandId.isNotBlank()) {
                    RemoteCommandRepository.recordAuthorization(applicationContext, commandId, authorized = false, reason = "RULE_BLOCKED")
                }
                config.appendWeComRemoteLog("规则阻止执行 -> ${command.targetNumber}$simSuffix")
                return
            }
        }

        if (commandId.isNotBlank()) {
            RemoteCommandRepository.recordAuthorization(applicationContext, commandId, authorized = true, reason = "WECOM_AUTHORIZED")
        }

        RemoteSmsCommandWorker.enqueue(
            context = applicationContext,
            target = command.targetNumber,
            content = command.content,
            subId = -1,
            uniqueId = fingerprint,
            sendMode = sendMode,
            requester = user,
            source = SOURCE_WECOM,
            commandId = commandId,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun updateNotification(status: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.wecom_remote_title))
            .setContentText(status)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, WeComRemoteControlSettingsActivity::class.java),
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
                    getString(R.string.wecom_remote_title),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        updateNotification("正在连接企业微信…")
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
        private const val TAG = "WeComRemoteControlService"
        private const val CHANNEL_ID = "wecom_remote_control"
        private const val NOTIFICATION_ID = 19085
        private const val POLL_INTERVAL_MS = 60_000L

        fun ensureStarted(context: Context) {
            val config = MultiForwardConfig(context)
            if (!config.weComRemoteControlEnabled) {
                context.stopService(Intent(context, WeComRemoteControlService::class.java))
                return
            }
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WeComRemoteControlService::class.java),
                )
            }.onFailure { error ->
                config.appendWeComRemoteLog("启动失败：${error.message ?: error.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WeComRemoteControlService::class.java))
        }
    }
}