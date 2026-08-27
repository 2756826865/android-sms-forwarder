package org.fossify.messages

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.rescheduleAllScheduledMessages
import org.fossify.messages.helpers.MessagingCache
import org.fossify.messages.messaging.SmsRecoveryWorker
import org.fossify.messages.helpers.LowBatteryCheckWorker
import org.fossify.messages.services.DingTalkRemoteControlService
import org.fossify.messages.services.SmsKeepAliveService
import org.fossify.messages.recovery.RecoveryEngine
import org.fossify.messages.recovery.RecoveryWorker
import org.fossify.messages.models.RecoveryTriggerSource

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
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

        ensureBackgroundThread {
            rescheduleAllScheduledMessages()
            kotlinx.coroutines.runBlocking {
                RecoveryEngine.runRecoveryScan(this@App, RecoveryTriggerSource.STARTUP)
            }
        }
        RecoveryWorker.schedule(this)
        SmsRecoveryWorker.schedule(this)
        LowBatteryCheckWorker.sync(this)
        org.fossify.messages.helpers.ShadowCleanupWorker.schedule(this)
        SmsKeepAliveService.ensureStarted(this)
        DingTalkRemoteControlService.ensureStarted(this)
    }

    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            MessagingCache.namePhoto.evictAll()
            MessagingCache.participantsCache.evictAll()
        }
    }
}
