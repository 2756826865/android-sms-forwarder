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
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.getSmsThreadId
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.insertNewSMS
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.syncThreadToLocal
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.forwarding.PushPlusWorker
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.messaging.SmsRecoveryWorker
import org.fossify.messages.models.Message
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Serial foreground owner for incoming SMS processing. The service remains
 * runnable while the screen is off, verifies provider persistence before
 * notifying or forwarding, and asks Android to redeliver an interrupted intent.
 */
class IncomingSmsService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        executor.execute {
            val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
            try {
                processIncoming(intent)
            } catch (error: Throwable) {
                Log.e(TAG, "incoming SMS processing failed", error)
                PushPlusConfig(applicationContext).lastReceiverStatus =
                    "广播已到达，处理失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                stopSelfResult(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun processIncoming(intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) {
            Log.w(TAG, "${intent.action} contained no SMS parts")
            return
        }

        val address = parts.last().originatingAddress.orEmpty()
        if (address.isBlank()) {
            Log.w(TAG, "incoming SMS has no originating address")
            return
        }

        val body = buildString { parts.forEach { append(it.messageBody.orEmpty()) } }
        val sentAt = parts.minOfOrNull { it.timestampMillis }
            ?.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val receivedAt = System.currentTimeMillis()
        val subscriptionId = intent.getIntExtra(
            "subscription",
            intent.getIntExtra("subscription_id", SubscriptionManager.INVALID_SUBSCRIPTION_ID),
        )
        val fingerprint = fingerprint(address, body, sentAt, subscriptionId)
        if (wasPersisted(fingerprint)) {
            Log.i(TAG, "duplicate ${intent.action} ignored after successful persistence")
            return
        }

        val receiverStatus = PushPlusConfig(applicationContext)
        receiverStatus.lastReceiverStatus =
            "已收到${intent.action?.substringAfterLast('.').orEmpty()}，正在写入短信库"

        if (isFiltered(address, body)) {
            Log.i(TAG, "incoming SMS from $address was filtered by user rules")
            return
        }

        val requestedThreadId = getThreadId(address)
        val insertedMessageId = persistWithRetry(
            address = address,
            subject = parts.last().pseudoSubject.orEmpty(),
            body = body,
            receivedAt = receivedAt,
            sentAt = sentAt,
            threadId = requestedThreadId,
            subscriptionId = subscriptionId,
        )
        val resolvedThreadId = getSmsThreadId(insertedMessageId)
            .takeIf { it > 0L }
            ?: requestedThreadId.takeIf { it > 0L }
            ?: error("短信已写入，但无法取得 thread_id")

        // Only a verified provider insert can suppress a second delivery action.
        markPersisted(fingerprint)
        SmsRecoveryWorker.markObserved(applicationContext, receivedAt)
        Log.i(
            TAG,
            "SMS persisted: id=$insertedMessageId thread=$resolvedThreadId action=${intent.action}",
        )

        runCatching {
            updateLocalDatabaseAndNotify(
                address = address,
                body = body,
                date = receivedAt,
                messageId = insertedMessageId,
                threadId = resolvedThreadId,
                subscriptionId = subscriptionId,
                status = parts.last().status,
            )
        }.onFailure { error ->
            Log.e(TAG, "system SMS persisted, local refresh failed", error)
            SmsRecoveryWorker.enqueueNow(applicationContext)
        }

        val uniqueId = "$insertedMessageId-$fingerprint"
        if (receiverStatus.enabled) {
            PushPlusWorker.enqueue(
                this,
                address,
                body,
                receivedAt,
                subscriptionId,
                uniqueId,
            )
        }
        if (MultiForwardConfig(this).anyEnabled()) {
            MultiChannelForwardWorker.enqueue(
                this,
                address,
                body,
                receivedAt,
                subscriptionId,
                uniqueId,
            )
        }
        receiverStatus.lastReceiverStatus =
            "已接收并写入短信库，短信ID：$insertedMessageId，发送方：$address"
    }

    private fun persistWithRetry(
        address: String,
        subject: String,
        body: String,
        receivedAt: Long,
        sentAt: Long,
        threadId: Long,
        subscriptionId: Int,
    ): Long {
        var lastError: Throwable? = null
        repeat(PROVIDER_ATTEMPTS) { attempt ->
            try {
                return insertNewSMS(
                    address = address,
                    subject = subject,
                    body = body,
                    date = receivedAt,
                    dateSent = sentAt,
                    read = 0,
                    threadId = threadId,
                    type = Telephony.Sms.MESSAGE_TYPE_INBOX,
                    subscriptionId = subscriptionId,
                )
            } catch (error: Throwable) {
                lastError = error
                Log.e(TAG, "SMS provider insert attempt ${attempt + 1} failed", error)
                if (attempt + 1 < PROVIDER_ATTEMPTS) Thread.sleep(PROVIDER_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("短信 Provider 写入失败")
    }

    private fun isFiltered(address: String, body: String): Boolean {
        val isWhitelisted = config.isNumberWhitelisted(address)
        if (!isWhitelisted && config.isNumberBlacklisted(address)) return true
        if (!isWhitelisted && isMessageFilteredOut(this, body)) return true
        if (!isWhitelisted && isNumberBlocked(address)) return true
        if (!isWhitelisted && baseConfig.blockUnknownNumbers) {
            val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            val result = SimpleContactsHelper(this).existsSync(address, privateCursor)
            if (result == ContactLookupResult.NotFound) return true
        }
        return false
    }

    private fun updateLocalDatabaseAndNotify(
        address: String,
        body: String,
        date: Long,
        messageId: Long,
        threadId: Long,
        subscriptionId: Int,
        status: Int,
    ) {
        val contacts = SimpleContactsHelper(this)
        val photoUri = contacts.getPhotoUriFromPhoneNumber(address)
        val senderName = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            getNameFromAddress(address, it)
        }
        val participant = SimpleContact(
            rawId = 0,
            contactId = 0,
            name = senderName,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(
                PhoneNumber(value = address, type = 0, label = "", normalizedNumber = address),
            ),
            birthdays = ArrayList(),
            anniversaries = ArrayList(),
        )
        val message = Message(
            id = messageId,
            body = body,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status = status,
            participants = arrayListOf(participant),
            date = (date / 1000).toInt(),
            read = false,
            threadId = threadId,
            isMMS = false,
            attachment = null,
            senderPhoneNumber = address,
            senderName = senderName,
            senderPhotoUri = photoUri,
            subscriptionId = subscriptionId,
        )

        messagesDB.insertOrUpdate(message)
        syncThreadToLocal(threadId)
        if (shouldUnarchive()) updateConversationArchivedStatus(threadId, false)
        refreshMessages()
        refreshConversations()
        showReceivedMessageNotification(
            messageId = messageId,
            address = address,
            senderName = senderName,
            body = body,
            threadId = threadId,
            bitmap = getNotificationBitmap(photoUri),
        )
    }

    private fun wasPersisted(fingerprint: String): Boolean =
        getSharedPreferences(DUPLICATE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_FINGERPRINT, null) == fingerprint

    private fun markPersisted(fingerprint: String) {
        getSharedPreferences(DUPLICATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_FINGERPRINT, fingerprint)
            .commit()
    }

    private fun fingerprint(
        address: String,
        body: String,
        sentAt: Long,
        subscriptionId: Int,
    ): String {
        val raw = "$address\u0000$body\u0000$sentAt\u0000$subscriptionId"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keep_alive_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.incoming_sms_processing))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

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
        private const val TAG = "IncomingSmsService"
        private const val CHANNEL_ID = "incoming_sms_processing"
        private const val NOTIFICATION_ID = 19082
        private const val WAKE_LOCK_TAG = "smsforwarder:incoming-service"
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
        private const val PROVIDER_ATTEMPTS = 3
        private const val PROVIDER_RETRY_DELAY_MS = 500L
        private const val DUPLICATE_PREFS = "sms_receiver_state"
        private const val KEY_LAST_FINGERPRINT = "last_fingerprint"

        fun enqueue(context: Context, source: Intent) {
            val serviceIntent = Intent(context, IncomingSmsService::class.java).apply {
                action = source.action
                replaceExtras(source)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
