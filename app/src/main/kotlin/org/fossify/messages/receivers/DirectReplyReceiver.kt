package org.fossify.messages.receivers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.RemoteInput
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.*
import org.fossify.messages.helpers.REPLY
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_NUMBER
import org.fossify.messages.messaging.sendMessageCompat

import org.fossify.messages.messaging.SimResolutionRequest
import org.fossify.messages.messaging.SubscriptionResolver
import org.fossify.messages.models.SmsSendTriggerType

class DirectReplyReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(THREAD_NUMBER)
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        var body = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY)?.toString() ?: return

        body = context.removeDiacriticsIfNeeded(body)
        if (body.isBlank()) return

        if (address != null) {
            val simResult = SubscriptionResolver.resolve(
                context,
                SimResolutionRequest(
                    targetAddress = address,
                    allowFallback = true
                )
            )
            val subscriptionId: Int? = if (simResult.isSuccessful) simResult.resolvedSubscriptionId else null

            val pendingResult = goAsync()
            val appContext = context.applicationContext
            ensureBackgroundThread {
                try {
                    var messageId = 0L
                    try {
                        appContext.sendMessageCompat(
                            text = body,
                            addresses = listOf(address),
                            subId = subscriptionId,
                            attachments = emptyList(),
                            propagateErrors = true,
                            triggerType = SmsSendTriggerType.DIRECT_REPLY
                        )
                        val message = appContext.getMessages(
                            threadId = threadId, includeScheduledMessages = false, limit = 1
                        ).lastOrNull()
                        if (message != null) {
                            appContext.messagesDB.insertOrUpdate(message)
                            messageId = message.id

                            appContext.updateLastConversationMessage(threadId)
                        }
                    } catch (e: Exception) {
                        appContext.showErrorToast(e)
                        return@ensureBackgroundThread
                    }

                    val photoUri = appContext.getNameAndPhotoFromPhoneNumber(address).photoUri.orEmpty()
                    val bitmap = appContext.getNotificationBitmap(photoUri)
                    Handler(Looper.getMainLooper()).post {
                        appContext.notificationHelper.showMessageNotification(
                            messageId = messageId,
                            address = address,
                            body = body,
                            threadId = threadId,
                            bitmap = bitmap,
                            sender = null,
                            alertOnlyOnce = true
                        )
                    }

                    appContext.markThreadMessagesRead(threadId)
                    appContext.conversationsDB.markRead(threadId)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
