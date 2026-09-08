package org.fossify.messages.services

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.NotificationForwardConfig
import org.fossify.messages.forwarding.repository.ChannelRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯旁路通知栏消息监听服务。
 *
 * 核心机制：
 * 1. 严格过滤自身发出的通知，杜绝死循环；
 * 2. 过滤常驻/媒体/下载类通知；
 * 3. 严格遵循用户勾选的应用白名单；
 * 4. 5 秒内内容指纹防抖，避免微信等即时通讯软件连续刷新通知触发轰炸；
 * 5. 异步投递给 MultiChannelForwardWorker，不阻塞系统通知总线。
 */
class NotificationForwardListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val packageName = sbn.packageName.orEmpty()
        // 1. 严格防御：杜绝自身通知循环转发
        if (packageName.isBlank() || packageName == this.packageName) return

        val config = NotificationForwardConfig(applicationContext)
        if (!config.enabled) return

        val notification = sbn.notification ?: return

        // 2. 过滤常驻与系统后台无意义通知（如音乐播放控制、下载进度、前台常驻服务）
        if (config.ignoreOngoing) {
            val flags = notification.flags
            val isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0
            val isLocalOnly = (flags and Notification.FLAG_LOCAL_ONLY) != 0
            if (isOngoing || isLocalOnly) return
        }

        // 3. 检查包名白名单（只处理用户勾选的应用）
        val targetPackages = config.targetPackageNames
        if (packageName !in targetPackages) return

        // 4. 提取通知文本（兼顾标准、BigTextStyle 与子文本）
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim().orEmpty()

        if (title.isBlank() && text.isBlank()) return

        // 5. 内容指纹防抖（5 秒内相同包名、标题和内容的通知只发一次）
        val deduplicateKey = "$packageName|$title|$text"
        val now = System.currentTimeMillis()
        cleanExpiredDeduplicationKeys(now)
        if (recentDeduplicationMap.putIfAbsent(deduplicateKey, now) != null) {
            // 命中防抖窗口，直接忽略
            return
        }

        val appName = getApplicationLabel(packageName)
        val bodyBuilder = StringBuilder().apply {
            appendLine("【$appName 通知】")
            if (title.isNotBlank()) {
                appendLine("发件/标题：$title")
            }
            append("内容：$text")
        }
        val forwardBody = bodyBuilder.toString()

        // 6. 靶向分发至已启用的各通道实例
        dispatchToChannels(config, appName, forwardBody, now, sbn.id)
    }

    private fun dispatchToChannels(
        config: NotificationForwardConfig,
        senderName: String,
        body: String,
        receivedAt: Long,
        notificationId: Int
    ) {
        val channelRepo = ChannelRepository.getInstance(applicationContext)
        val allEnabledInstances = channelRepo.getEnabledInstances().filterNot { it.id.startsWith("catalog:") }

        val targetInstances = if (config.hasChannelSelection) {
            val selectedIds = config.channelInstanceIds
            allEnabledInstances.filter { it.id in selectedIds }
        } else {
            allEnabledInstances
        }

        val multiConfig = MultiForwardConfig(applicationContext)
        val legacyChannels = if (!config.hasChannelSelection) {
            val instanceTypes = allEnabledInstances.map { it.channelType }.toSet()
            multiConfig.enabledChannelIds().filterNot { it in instanceTypes }
        } else {
            emptyList()
        }

        if (targetInstances.isEmpty() && legacyChannels.isEmpty()) {
            Log.d(TAG, "Notification forward skipped: No enabled channel found.")
            return
        }

        val uniqueBase = "notify-${System.currentTimeMillis()}-$notificationId"

        targetInstances.forEach { instance ->
            MultiChannelForwardWorker.enqueueSingle(
                context = applicationContext,
                sender = senderName,
                body = body,
                receivedAt = receivedAt,
                subscriptionId = -1,
                uniqueId = "$uniqueBase-${instance.id}",
                targetChannel = instance.channelType,
                targetInstanceId = instance.id,
                allowedChannels = setOf(instance.id),
                bodyAlreadyRendered = true
            )
        }

        legacyChannels.forEach { channel ->
            MultiChannelForwardWorker.enqueueSingle(
                context = applicationContext,
                sender = senderName,
                body = body,
                receivedAt = receivedAt,
                subscriptionId = -1,
                uniqueId = "$uniqueBase-legacy-$channel",
                targetChannel = channel,
                allowedChannels = setOf(channel),
                bodyAlreadyRendered = true
            )
        }
    }

    private fun getApplicationLabel(packageName: String): String {
        appNameCache[packageName]?.let { return it }
        val label = runCatching {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
        appNameCache[packageName] = label
        return label
    }

    companion object {
        private const val TAG = "NotifyForwardService"
        private const val DEDUPLICATE_WINDOW_MS = 5_000L
        private val recentDeduplicationMap = ConcurrentHashMap<String, Long>()
        private val appNameCache = ConcurrentHashMap<String, String>()

        private fun cleanExpiredDeduplicationKeys(currentTime: Long) {
            if (recentDeduplicationMap.size > 200) {
                val iterator = recentDeduplicationMap.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (currentTime - entry.value > DEDUPLICATE_WINDOW_MS) {
                        iterator.remove()
                    }
                }
                if (recentDeduplicationMap.size > 500) {
                    recentDeduplicationMap.clear()
                }
            }
        }

        /**
         * 检测是否已经获得系统“通知使用权”授权
         */
        fun isNotificationAccessGranted(context: Context): Boolean {
            val fromManager = runCatching {
                NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            }.getOrDefault(false)
            if (fromManager) return true

            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val myComponent = ComponentName(context, NotificationForwardListenerService::class.java).flattenToString()
            return enabledListeners.contains(myComponent) || enabledListeners.contains(context.packageName)
        }

        fun isPermissionGranted(context: Context): Boolean = isNotificationAccessGranted(context)

        /**
         * 引导用户前往系统设置授予通知监听权限
         */
        fun requestNotificationAccess(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        fun requestPermission(context: Context) = requestNotificationAccess(context)

        /**
         * 针对定制系统在后台偶尔掉线时的无感自愈重连
         */
        fun rebindService(context: Context) {
            runCatching {
                val config = NotificationForwardConfig(context)
                if (!config.enabled) return
                if (isNotificationAccessGranted(context)) {
                    val component = ComponentName(context, NotificationForwardListenerService::class.java)
                    val pm = context.packageManager
                    pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }

        /**
         * 发送一条模拟的通知转发测试消息
         */
        fun testForward(context: Context) {
            val appContext = context.applicationContext
            val config = NotificationForwardConfig(appContext)
            val now = System.currentTimeMillis()
            val dummySender = "微信"
            val dummyBody = "【微信 通知】\n发件/标题：测试好友/群聊\n内容：这是一条来自短信转发器的模拟通知转发测试消息，用于验证通道配置与连通性。"

            val channelRepo = ChannelRepository.getInstance(appContext)
            val allEnabledInstances = channelRepo.getEnabledInstances().filterNot { it.id.startsWith("catalog:") }

            val targetInstances = if (config.hasChannelSelection) {
                val selectedIds = config.channelInstanceIds
                allEnabledInstances.filter { it.id in selectedIds }
            } else {
                allEnabledInstances
            }

            val multiConfig = MultiForwardConfig(appContext)
            val legacyChannels = if (!config.hasChannelSelection) {
                val instanceTypes = allEnabledInstances.map { it.channelType }.toSet()
                multiConfig.enabledChannelIds().filterNot { it in instanceTypes }
            } else {
                emptyList()
            }

            val uniqueBase = "test-notify-$now"
            targetInstances.forEach { instance ->
                MultiChannelForwardWorker.enqueueSingle(
                    context = appContext,
                    sender = dummySender,
                    body = dummyBody,
                    receivedAt = now,
                    subscriptionId = -1,
                    uniqueId = "$uniqueBase-${instance.id}",
                    targetChannel = instance.channelType,
                    targetInstanceId = instance.id,
                    allowedChannels = setOf(instance.id),
                    isTest = true,
                    bodyAlreadyRendered = true
                )
            }

            legacyChannels.forEach { channel ->
                MultiChannelForwardWorker.enqueueSingle(
                    context = appContext,
                    sender = dummySender,
                    body = dummyBody,
                    receivedAt = now,
                    subscriptionId = -1,
                    uniqueId = "$uniqueBase-legacy-$channel",
                    targetChannel = channel,
                    allowedChannels = setOf(channel),
                    isTest = true,
                    bodyAlreadyRendered = true
                )
            }
        }
    }
}
