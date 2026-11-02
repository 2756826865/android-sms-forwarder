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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
import org.fossify.messages.remote.repository.RemoteSourceConnectionState
import org.fossify.messages.remote.repository.RemoteSourceRepository
import org.fossify.messages.remote.repository.RemoteSourceType
import org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager

/**
 * One foreground owner for SMS guarding and all configured remote listeners. Keep this component
 * name stable for existing watchdogs and upgrade restarts.
 */
class SmsKeepAliveService : Service() {
    private var startupFailed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastText: String? = null
    private var latestStartId = 0

    // Framework/OEM boundary: record failures without terminating SMS processing.
    @Suppress("TooGenericExceptionCaught")
    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            val notification = buildNotification(getString(R.string.background_service_starting))
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
            retireLegacyServices(this)
            scope.launch {
                RemoteSourceRepository.getInstance(applicationContext)
                    .sourcesFlow
                    .map { snapshot(applicationContext, it) }
                    .distinctUntilChanged()
                    .sample(NOTIFICATION_REFRESH_MS)
                    .catch { error ->
                        record(applicationContext, "failed", error.javaClass.simpleName)
                        Log.w("SmsKeepAlive", "state observer failed", error)
                    }
                    .collect { _ ->
                        // Read current configuration on the main thread before acting: a queued
                        // repository emission must not stop a newly enabled service.
                        runCatching { reconcile(snapshot(applicationContext), latestStartId) }
                            .onFailure { Log.w("SmsKeepAlive", "notification update failed", it) }
                    }
            }
        } catch (error: Exception) {
            running = false
            startupFailed = true
            record(this, "failed", error.javaClass.simpleName)
            Log.w("SmsKeepAlive", "foreground failed: ${error.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.background_service_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        running = false
        if (!startupFailed) record(this, "stopped", "")
        super.onDestroy()
    }

    @Suppress("TooGenericExceptionCaught")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        latestStartId = startId
        retireLegacyServices(this)
        return try {
            val state = snapshot(applicationContext)
            // Explicit re-entry also follows notification permission/channel changes. A prior
            // notify may have been suppressed by the system even when its text is unchanged.
            lastText = null
            reconcile(state, startId)
            if (state.required)
                RemoteSourceRuntimeManager.restoreAfterServiceRestart(applicationContext)
            if (state.required) START_STICKY else START_NOT_STICKY
        } catch (error: Exception) {
            record(this, "failed", error.javaClass.simpleName)
            Log.w("SmsKeepAlive", "configuration reconciliation failed", error)
            // Retain the existing foreground owner; a later configuration update can retry.
            START_STICKY
        }
    }

    private fun reconcile(state: BackgroundServiceSnapshot, startId: Int) {
        if (!state.required) {
            if (startId > 0) stopSelfResult(startId)
        } else {
            if (state.text != lastText) {
                lastText = state.text
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(state.text))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keep_alive_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = getString(R.string.keep_alive_channel_description)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
        )
    }

    companion object {
        private const val NOTIFICATION_REFRESH_MS = 500L
        private const val CHANNEL_ID = "sms_background_service"
        internal const val NOTIFICATION_ID = 19081
        internal val LEGACY_NOTIFICATION_IDS = setOf(19083, 19084, 19086, 19087, 19088, 19090)

        private fun smsGuardEnabled(context: Context): Boolean =
            org.fossify.messages.forwarding.MultiForwardConfig(context).keepAliveServiceEnabled

        internal fun snapshot(
            context: Context,
            sources: List<org.fossify.messages.remote.repository.RemoteSourceInstance> =
                RemoteSourceRepository.getInstance(context).sourcesFlow.value,
            activeSourceIds: Set<String> = RemoteSourceRuntimeManager.activeSourceIds(),
        ): BackgroundServiceSnapshot {
            val enabled = sources.filter { it.enabled && it.type != RemoteSourceType.SMS }
            val runnable = enabled.filter { it.hasValidCredentials() }
            val ready = runnable.count {
                it.id in activeSourceIds && it.connectionState == RemoteSourceConnectionState.READY
            }
            val detail =
                enabled
                    .groupBy { it.type }
                    .entries
                    .sortedBy { it.key.ordinal }
                    .joinToString("；") { (type, instances) ->
                        "${type.label}：" +
                            instances
                                .groupingBy {
                                    when {
                                        !it.hasValidCredentials() ->
                                            RemoteSourceConnectionState.CONFIG_REQUIRED.label
                                        it.id !in activeSourceIds ->
                                            context.getString(R.string.background_remote_restoring)
                                        else -> it.connectionState.label
                                    }
                                }
                                .eachCount()
                                .entries
                                .sortedBy { it.key }
                                .joinToString("、") { "${it.key} ${it.value}" }
                    }
            val guard = smsGuardEnabled(context)
            val summary =
                listOfNotNull(
                        context.getString(
                            if (guard) R.string.background_sms_on else R.string.background_sms_off
                        ),
                        if (enabled.isNotEmpty())
                            context.getString(R.string.background_remote_count, ready, enabled.size)
                        else null,
                    )
                    .joinToString(" · ")
            return BackgroundServiceSnapshot(
                guard,
                runnable.size,
                summary + if (detail.isNotEmpty()) "\n$detail" else "",
            )
        }

        internal fun retireLegacyServices(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            listOf(
                    DingTalkRemoteControlService::class.java,
                    FeishuRemoteControlService::class.java,
                    EmailRemoteControlService::class.java,
                    TelegramRemoteControlService::class.java,
                    WebSocketRemoteControlService::class.java,
                    WeComRemoteControlService::class.java,
                )
                .forEach {
                    context.stopService(Intent(context, it))
                }
            for (id in LEGACY_NOTIFICATION_IDS) manager.cancel(id)
        }

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
            val configured = if (smsGuardEnabled(context)) "开启" else "关闭"
            val actual =
                if (running) "已完成前台化"
                else if (p.getString("state", "") == "failed") "启动失败：${p.getString("reason", "")}"
                else "未运行或待确认"
            return "短信守护设置：$configured；统一后台服务：$actual；最近变化：${p.getLong("at",0L)}"
        }

        // Framework/OEM boundary: retain durable state and report unclassified platform failures.
        @Suppress("TooGenericExceptionCaught")
        fun ensureStarted(context: Context): StartResult {
            return try {
                if (!running && !smsGuardEnabled(context) && !snapshot(context).required) {
                    retireLegacyServices(context)
                    return StartResult.DISABLED
                }
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
            // A caller disabling SMS or one source must not stop the other enabled listeners.
            ensureStarted(context)
        }
    }
}
