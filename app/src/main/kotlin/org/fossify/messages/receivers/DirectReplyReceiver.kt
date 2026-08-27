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

        if (address != null) {
            val simResult = SubscriptionResolver.resolve(
                context,
                SimResolutionRequest(
                    targetAddress = address,
                    allowFallback = true
                )
            )
            val subscriptionId: Int? = if (simResult.isSuccessful) simResult.resolvedSubscriptionId else null

            ensureBackgroundThread {
                var messageId = 0L
                try {
                    context.sendMessageCompat(
                        text = body,
                        addresses = listOf(address),
                        subId = subscriptionId,
                        attachments = emptyList(),
                        triggerType = SmsSendTriggerType.DIRECT_REPLY
                    )
                    val message = context.getMessages(
                        threadId = threadId, includeScheduledMessages = false, limit = 1
                    ).lastOrNull()
                    if (message != null) {
                        context.messagesDB.insertOrUpdate(message)
                        messageId = message.id

                        context.updateLastConversationMessage(threadId)
                    }
                } catch (e: Exception) {
                    context.showErrorToast(e)
                }

                val photoUri = context.getNameAndPhotoFromPhoneNumber(address).photoUri.orEmpty()
                val bitmap = context.getNotificationBitmap(photoUri)
                Handler(Looper.getMainLooper()).post {
                    context.notificationHelper.showMessageNotification(
                        messageId = messageId,
                        address = address,
                        body = body,
                        threadId = threadId,
                        bitmap = bitmap,
                        sender = null,
                        alertOnlyOnce = true
                    )
                }

                context.markThreadMessagesRead(threadId)
                context.conversationsDB.markRead(threadId)
            }
        }
    }
}
