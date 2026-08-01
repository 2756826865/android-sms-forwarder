package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.insertNewSMS
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.forwarding.PushPlusWorker
import org.fossify.messages.models.Message
import java.security.MessageDigest

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext

        ensureBackgroundThread {
            try {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isEmpty()) return@ensureBackgroundThread

                // this is how it has always worked, but need to revisit this.
                val address = parts.last().originatingAddress.orEmpty()
                if (address.isBlank()) return@ensureBackgroundThread
                val subject = parts.last().pseudoSubject.orEmpty()
                val status = parts.last().status
                val body = buildString { parts.forEach { append(it.messageBody.orEmpty()) } }
                val receivedAt = parts.minOfOrNull { it.timestampMillis }
                    ?.takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                val subscriptionId = intent.getIntExtra(
                    "subscription",
                    intent.getIntExtra("subscription_id", -1)
                )

                if (isDuplicate(appContext, address, body, receivedAt, subscriptionId)) {
                    return@ensureBackgroundThread
                }

                if (isMessageFilteredOut(appContext, body)) return@ensureBackgroundThread
                if (appContext.isNumberBlocked(address)) return@ensureBackgroundThread
                if (appContext.baseConfig.blockUnknownNumbers) {
                    val privateCursor =
                        appContext.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                    val result = SimpleContactsHelper(appContext).existsSync(address, privateCursor)
                    if (result == ContactLookupResult.NotFound) return@ensureBackgroundThread
                }

                val date = receivedAt
                val threadId = appContext.getThreadId(address)

                if (PushPlusConfig(appContext).enabled) {
                    PushPlusWorker.enqueue(
                        context = appContext,
                        sender = address,
                        body = body,
                        receivedAt = date,
                        subscriptionId = subscriptionId,
                        uniqueId = "$date-${address.hashCode()}-${body.hashCode()}"
                    )
                }

                runCatching {
                    handleMessageSync(
                        context = appContext,
                        address = address,
                        subject = subject,
                        body = body,
                        date = date,
                        threadId = threadId,
                        subscriptionId = subscriptionId,
                        status = status
                    )
                }.onFailure { error ->
                    PushPlusConfig(appContext).lastStatus =
                        "已收到短信；本机短信库写入失败：${error.message ?: error.javaClass.simpleName}"
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleMessageSync(
        context: Context,
        address: String,
        subject: String,
        body: String,
        date: Long,
        read: Int = 0,
        threadId: Long,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        subscriptionId: Int,
        status: Int
    ) {
        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        val bitmap = context.getNotificationBitmap(photoUri)

        val newMessageId = context.insertNewSMS(
            address = address,
            subject = subject,
            body = body,
            date = date,
            read = read,
            threadId = threadId,
            type = type,
            subscriptionId = subscriptionId
        )

        context.getConversations(threadId).firstOrNull()?.let { conv ->
            runCatching { context.insertOrUpdateConversation(conv) }
        }

        val senderName = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            context.getNameFromAddress(address, it)
        }

        val participant = SimpleContact(
            rawId = 0,
            contactId = 0,
            name = senderName,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(PhoneNumber(value = address, type = 0, label = "", normalizedNumber = address)),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )

        val message = Message(
            id = newMessageId,
            body = body,
            type = type,
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
            subscriptionId = subscriptionId
        )

        context.messagesDB.insertOrUpdate(message)

        if (context.shouldUnarchive()) {
            context.updateConversationArchivedStatus(threadId, false)
        }

        refreshMessages()
        refreshConversations()
        context.showReceivedMessageNotification(
            messageId = newMessageId,
            address = address,
            senderName = senderName,
            body = body,
            threadId = threadId,
            bitmap = bitmap
        )
    }

    private fun isDuplicate(
        context: Context,
        address: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int
    ): Boolean = synchronized(duplicateLock) {
        val raw = "$address\u0000$body\u0000$receivedAt\u0000$subscriptionId"
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val preferences = context.getSharedPreferences(DUPLICATE_PREFS, Context.MODE_PRIVATE)
        val previous = preferences.getString(KEY_LAST_FINGERPRINT, null)
        preferences.edit()
            .putString(KEY_LAST_FINGERPRINT, fingerprint)
            .apply()
        previous == fingerprint
    }

    companion object {
        private const val DUPLICATE_PREFS = "sms_receiver_state"
        private const val KEY_LAST_FINGERPRINT = "last_fingerprint"
        private val duplicateLock = Any()
    }
}
