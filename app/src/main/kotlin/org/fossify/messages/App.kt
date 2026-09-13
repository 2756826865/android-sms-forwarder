package org.fossify.messages

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.databases.MessagesDatabase
import org.fossify.messages.extensions.rescheduleAllScheduledMessages
import org.fossify.messages.helpers.DatabaseRecoveryNotifier
import org.fossify.messages.helpers.MessagingCache
import org.fossify.messages.messaging.SmsRecoveryWorker
import org.fossify.messages.helpers.LowBatteryCheckWorker
import org.fossify.messages.helpers.HeartbeatWorker
import org.fossify.messages.services.DingTalkRemoteControlService
import org.fossify.messages.services.SmsKeepAliveService
import org.fossify.messages.recovery.RecoveryEngine
import org.fossify.messages.recovery.RecoveryWorker
import org.fossify.messages.models.RecoveryTriggerSource

import org.fossify.messages.extensions.config

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    private companion object {
        const val TAG = "App"
    }

    override fun onCreate() {
        super.onCreate()
        config.primaryColor = getColor(R.color.miui_action_blue)
        config.accentColor = getColor(R.color.miui_fab_green)
        getSharedPreferences("Prefs", MODE_PRIVATE)
            .edit()
            .remove("app_sideloading_status")
            .apply()
        
        // 彻底清理黄页功能残留的配置文件
        getSharedPreferences("yellow_pages_meta", MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        if (hasPermission(PERMISSION_READ_CONTACTS)) {
            listOf(
                ContactsContract.Contacts.CONTENT_URI,
                ContactsContract.Data.CONTENT_URI,
                ContactsContract.DisplayPhoto.CONTENT_URI
            ).forEach {
                try {
                    contentResolver.registerContentObserver(it, true, contactsObserver)
                } catch (_: Exception) {
                }
            }
        }

        // 后台预热本地库：把 Room 的建库/迁移从「首次 DAO 调用」提前到启动阶段。
        // 从很旧的版本直接跨版本升级时 MIGRATION_2_3 会做一次 conversations 数据拷贝，
        // 放在 IO 线程预热可以避免在主线程触发这段耗时。
        // 顺序要求：必须先 getInstance()（降级与恢复标志只发生在它内部），再检查恢复模式。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { MessagesDatabase.getInstance(this@App) }
                .onFailure { Log.e(TAG, "本地数据库预热失败", it) }
            DatabaseRecoveryNotifier.notifyIfNeeded(this@App)
        }

        ensureBackgroundThread {
            rescheduleAllScheduledMessages()
            kotlinx.coroutines.runBlocking {
                RecoveryEngine.runRecoveryScan(this@App, RecoveryTriggerSource.STARTUP)
            }
        }
        RecoveryWorker.schedule(this)
        SmsRecoveryWorker.schedule(this)
        LowBatteryCheckWorker.sync(this)
        HeartbeatWorker.sync(this)
        org.fossify.messages.helpers.ShadowCleanupWorker.schedule(this)
        SmsKeepAliveService.ensureStarted(this)
        // 应用启动即触发旧版通道的幂等自动迁移与长连接预热，放后台 IO 协程执行，绝不阻塞主线程冷启动。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                org.fossify.messages.forwarding.repository.ChannelRepository.getInstance(this@App)
                org.fossify.messages.remote.repository.RemoteSourceRepository.getInstance(this@App).importLegacySources()
                org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager.getInstance(this@App).sync()
            }.onFailure { error ->
                Log.e(TAG, "App background init failed", error)
            }
        }
    }

    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            MessagingCache.namePhoto.evictAll()
            MessagingCache.participantsCache.evictAll()
        }
    }
}
