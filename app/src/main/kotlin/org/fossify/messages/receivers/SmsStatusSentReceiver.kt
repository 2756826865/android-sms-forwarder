package org.fossify.messages.receivers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.getMessageRecipientAddress
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getSmsThreadId
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.extensions.notificationHelper
import org.fossify.messages.extensions.syncThreadToLocal
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.remote.RemoteControlReceiptForwarder
import org.fossify.messages.receivers.SendStatusReceiver
import org.fossify.commons.models.SimpleContact
import org.fossify.commons.models.PhoneNumber

/** Handles updating databases and states when a SMS message is sent. */
class SmsStatusSentReceiver : SendStatusReceiver() {

    override fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        val messageUri: Uri? = intent.data
        val resultCode = resultCode
        Log.i(TAG, "updateAndroidDatabase: uri=$messageUri, resultCode=$resultCode")
        val messagingUtils = context.messagingUtils

        val type = if (resultCode == Activity.RESULT_OK) {
            Sms.MESSAGE_TYPE_SENT
        } else {
            Sms.MESSAGE_TYPE_FAILED
        }
        messagingUtils.updateSmsMessageSendingStatus(messageUri, type)
        messagingUtils.maybeShowErrorToast(
            resultCode = resultCode,
            errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, NO_ERROR_CODE)
        )
    }

    override fun updateAppDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        val messageUri = intent.data
        Log.i(TAG, "updateAppDatabase: uri=$messageUri, resultCode=$receiverResultCode")
        if (messageUri != null) {
            val messageId = messageUri.lastPathSegment?.toLong() ?: 0L
            val intentThreadId = intent.getLongExtra(SendStatusReceiver.EXTRA_THREAD_ID, 0L)
            val intentAddress = intent.getStringExtra(SendStatusReceiver.EXTRA_ADDRESS) ?: ""

            ensureBackgroundThread {
                val type = if (receiverResultCode == Activity.RESULT_OK) {
                    Sms.MESSAGE_TYPE_SENT
                } else {
                    showSendingFailedNotification(context, messageId)
                    Sms.MESSAGE_TYPE_FAILED
                }

                // Log local state before update
                val localBefore = context.messagesDB.getMessageWithId(messageId)
                
                // 1. Repair from Intent if local record is missing
                if (localBefore == null && intentThreadId != 0L && intentAddress.isNotBlank()) {
                    val participant = SimpleContact(
                        rawId = 0, contactId = 0, name = intentAddress, photoUri = "",
                        phoneNumbers = arrayListOf(PhoneNumber(intentAddress, 0, "", intentAddress)),
                        birthdays = ArrayList(), anniversaries = ArrayList()
                    )
                    val androidBody = context.contentResolver.query(messageUri, arrayOf(Sms.BODY), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else ""
                    } ?: ""
                    
                    val repairedMsg = org.fossify.messages.models.Message(
                        id = messageId, body = androidBody, type = type, status = Sms.STATUS_NONE,
                        participants = arrayListOf(participant), date = (System.currentTimeMillis() / 1000).toInt(),
                        read = true, threadId = intentThreadId, isMMS = false, attachment = null,
                        senderPhoneNumber = intentAddress, senderName = intentAddress, senderPhotoUri = "",
                        subscriptionId = intent.getIntExtra(SendStatusReceiver.EXTRA_SUB_ID, -1)
                    )
                    context.messagesDB.insertOrUpdate(repairedMsg)
                    Log.d("MessagingDebug", "LocalDB repaired from Intent: msgId=$messageId, threadId=$intentThreadId")
                } else {
                    // 2. Standard update
                    context.messagesDB.updateType(messageId, type)
                }

                val localAfter = context.messagesDB.getMessageWithId(messageId)
                
                // 3. System Provider info
                val androidThreadId = context.getSmsThreadId(messageId)
                val androidAddress = context.getMessageRecipientAddress(messageId)

                // 4. Final Sync/Refresh
                val finalThreadId = when {
                    androidThreadId != 0L -> androidThreadId
                    localAfter?.threadId != null && localAfter.threadId != 0L -> localAfter.threadId
                    else -> intentThreadId
                }
                val finalAddress = when {
                    androidAddress.isNotBlank() -> androidAddress
                    localAfter?.senderPhoneNumber != null && localAfter.senderPhoneNumber.isNotBlank() -> localAfter.senderPhoneNumber
                    else -> intentAddress
                }

                if (finalThreadId != 0L) {
                    context.syncThreadToLocal(finalThreadId, address = finalAddress)
                }

                RemoteControlReceiptForwarder.onSendResult(
                    context = context,
                    messageId = messageId,
                    resultCode = receiverResultCode,
                    errorCode = intent.getIntExtra(SendStatusReceiver.EXTRA_ERROR_CODE, SendStatusReceiver.NO_ERROR_CODE),
                )
                refreshMessages()
                refreshConversations()
            }
        }
    }

    private fun showSendingFailedNotification(context: Context, messageId: Long) {
        Handler(Looper.getMainLooper()).post {
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                return@post
            }
            val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            ensureBackgroundThread {
                val address = context.getMessageRecipientAddress(messageId)
                val threadId = context.getThreadId(address)
                val recipientName = context.getNameFromAddress(address, privateCursor)
                context.notificationHelper.showSendingFailedNotification(recipientName, threadId)
            }
        }
    }

    private companion object {
        const val TAG = "SmsStatusSent"
    }
}
