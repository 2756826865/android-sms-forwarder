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
import org.fossify.messages.services.DingTalkRemoteControlService
import org.fossify.messages.services.SmsKeepAliveService

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("Prefs", MODE_PRIVATE)
            .edit()
            .remove("app_sideloading_status")
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
        }
        SmsRecoveryWorker.schedule(this)
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
