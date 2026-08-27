package org.fossify.messages.messaging

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.widget.Toast
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.R
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.isPlainTextMimeType
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.smsSender
import org.fossify.messages.helpers.DeviceCompatHelper
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.messaging.SmsException.Companion.ERROR_PERSISTING_MESSAGE
import org.fossify.messages.models.Attachment
import org.fossify.messages.models.SmsSendContext
import org.fossify.messages.models.SmsSendTriggerType
import org.fossify.messages.receivers.MmsSentReceiver
import org.fossify.messages.receivers.SendStatusReceiver

class MessagingUtils(val context: Context) {

    /**
     * Insert an SMS to the given URI with thread_id specified.
     */
    private fun insertSmsMessage(
        subId: Int,
        dest: String,
        text: String,
        timestamp: Long,
        threadId: Long,
        status: Int = Sms.STATUS_NONE,
        type: Int = Sms.MESSAGE_TYPE_OUTBOX,
        messageId: Long? = null
    ): Uri {
        val resolvedThreadId = when {
            threadId > 0L -> threadId
            else -> context.getThreadId(dest)
        }
        if (resolvedThreadId <= 0L) {
            throw SmsException(ERROR_PERSISTING_MESSAGE)
        }

        val values = ContentValues().apply {
            put(Sms.ADDRESS, dest)
            put(Sms.DATE, timestamp)
            put(Sms.DATE_SENT, timestamp)
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.BODY, text)
            put(Sms.THREAD_ID, resolvedThreadId)
            put(Sms.CREATOR, context.packageName)

            if (subId != Settings.DEFAULT_SUBSCRIPTION_ID) {
                put(Sms.SUBSCRIPTION_ID, subId)
                if (needsOemSimIdColumn()) {
                    put(COLUMN_SIM_ID, subId.toLong())
                }
            }

            if (status != Sms.STATUS_NONE) put(Sms.STATUS, status)
            if (type != Sms.MESSAGE_TYPE_ALL) put(Sms.TYPE, type)
        }

        try {
            val response: Uri?
            if (messageId != null) {
                val selection = "${Sms._ID} = ?"
                val selectionArgs = arrayOf(messageId.toString())
                val count = context.contentResolver.update(Sms.CONTENT_URI, values, selection, selectionArgs)
                response = if (count > 0) Uri.parse("${Sms.CONTENT_URI}/${messageId}") else null
            } else {
                response = context.contentResolver.insert(Sms.CONTENT_URI, values)
            }
            val inserted = response ?: throw SmsException(ERROR_PERSISTING_MESSAGE)
            runCatching {
                context.contentResolver.notifyChange(Telephony.MmsSms.CONTENT_CONVERSATIONS_URI, null)
            }
            return inserted
        } catch (e: Exception) {
            throw SmsException(ERROR_PERSISTING_MESSAGE, e)
        }
    }

    /** Send an SMS message and ensures LocalDB identity is established first. */
    fun sendSmsMessage(
        text: String,
        addresses: Set<String>,
        subId: Int,
        requireDeliveryReport: Boolean,
        messageId: Long? = null,
        triggerType: SmsSendTriggerType = SmsSendTriggerType.LEGACY_UNKNOWN
    ): List<Uri> {
        val sentUris = mutableListOf<Uri>()
        if (addresses.size > 1) {
            val broadCastThreadId = context.getThreadId(addresses.toSet())
            val mergedAddresses = addresses.joinToString(ADDRESS_SEPARATOR)
            insertSmsMessage(
                subId = subId, dest = mergedAddresses, text = text,
                timestamp = System.currentTimeMillis(), threadId = broadCastThreadId,
                status = Sms.Sent.STATUS_COMPLETE, type = Sms.Sent.MESSAGE_TYPE_SENT,
                messageId = messageId
            )
        }

        for (address in addresses) {
            val threadId = context.getThreadId(address)
            if (threadId <= 0L) throw SmsException(ERROR_PERSISTING_MESSAGE)
            
            val messageUri = insertSmsMessage(
                subId = subId, dest = address, text = text,
                timestamp = System.currentTimeMillis(), threadId = threadId,
                messageId = messageId
            )
            
            val insertedId = messageUri.lastPathSegment?.toLongOrNull() ?: 0L
            if (insertedId > 0L) {
                val participant = SimpleContact(
                    rawId = 0, contactId = 0, name = address, photoUri = "",
                    phoneNumbers = arrayListOf(PhoneNumber(address, 0, "", address)),
                    birthdays = ArrayList(), anniversaries = ArrayList()
                )
                val localMessage = org.fossify.messages.models.Message(
                    id = insertedId, body = text, type = Sms.MESSAGE_TYPE_SENT, status = Sms.STATUS_NONE,
                    participants = arrayListOf(participant), date = (System.currentTimeMillis() / 1000).toInt(),
                    read = true, threadId = threadId, isMMS = false, attachment = null,
                    senderPhoneNumber = address, senderName = address, senderPhotoUri = "", subscriptionId = subId
                )
                
                context.messagesDB.insertOrUpdate(localMessage)
                val verify = context.messagesDB.getMessageWithId(insertedId)
                val isVerified = verify != null && verify.threadId == threadId && verify.senderPhoneNumber == address
                
                if (!isVerified) {
                    android.util.Log.e("MessagingDebug", "LocalDB preinsert VERIFY FAILED! msgId=$insertedId, targetThreadId=$threadId")
                    return emptyList()
                }
            }

            val sendOperationId = SmsSendCoordinator.beginSend(
                context,
                SmsSendContext(
                    triggerType = triggerType,
                    address = address,
                    body = text,
                    subscriptionId = subId,
                    threadId = threadId,
                    requireDeliveryReport = requireDeliveryReport,
                    messageUri = messageUri.toString()
                )
            )

            try {
                context.smsSender.sendMessage(
                    subId = subId, destination = address, body = text, serviceCenter = null,
                    requireDeliveryReport = requireDeliveryReport, messageUri = messageUri, threadId = threadId,
                    sendOperationId = sendOperationId
                )
                sentUris += messageUri
            } catch (e: Exception) {
                updateSmsMessageSendingStatus(messageUri, Sms.Outbox.MESSAGE_TYPE_FAILED)
                throw e
            }
        }
        refreshConversations()
        return sentUris
    }

    private fun needsOemSimIdColumn(): Boolean {
        return when (DeviceCompatHelper.detectBrand()) {
            DeviceCompatHelper.DeviceBrand.XIAOMI,
            DeviceCompatHelper.DeviceBrand.REDMI,
            DeviceCompatHelper.DeviceBrand.POCO -> true
            else -> false
        }
    }

    fun updateSmsMessageSendingStatus(messageUri: Uri?, type: Int) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(Sms.Outbox.TYPE, type)
            if (type == Sms.MESSAGE_TYPE_SENT) put(Sms.DATE_SENT, System.currentTimeMillis())
        }

        try {
            if (messageUri != null) {
                resolver.update(messageUri, values, null, null)
            } else {
                val cursor = resolver.query(Sms.Outbox.CONTENT_URI, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        @SuppressLint("Range")
                        val id = it.getString(it.getColumnIndex(Sms.Outbox._ID))
                        resolver.update(Sms.Outbox.CONTENT_URI, values, "${Sms._ID} = ?", arrayOf(id))
                    }
                }
            }
            runCatching { resolver.notifyChange(Telephony.MmsSms.CONTENT_CONVERSATIONS_URI, null) }
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun getSmsMessageFromDeliveryReport(intent: Intent): SmsMessage? {
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")
        return SmsMessage.createFromPdu(pdu, format)
    }

    @Deprecated("TODO: Move/rewrite MMS code into the app.")
    fun sendMmsMessage(
        text: String,
        addresses: List<String>,
        attachment: Attachment?,
        settings: Settings,
        messageId: Long? = null,
        propagateErrors: Boolean = false,
    ) {
        val transaction = Transaction(context, settings)
        val message = Message(text, addresses.toTypedArray())

        if (attachment != null) {
            try {
                val uri = attachment.getUri()
                context.contentResolver.openInputStream(uri)?.use {
                    val bytes = it.readBytes()
                    val mimeType = if (attachment.mimetype.isPlainTextMimeType()) "application/txt" else attachment.mimetype
                    message.addMedia(bytes, mimeType, attachment.filename, attachment.filename)
                }
            } catch (e: Exception) {
                if (propagateErrors) throw e
                context.showErrorToast(e)
            }
        }

        val mmsSentIntent = Intent(context, MmsSentReceiver::class.java)
        mmsSentIntent.putExtra(MmsSentReceiver.EXTRA_ORIGINAL_RESENT_MESSAGE_ID, messageId)
        transaction.setExplicitBroadcastForSentMms(mmsSentIntent)

        try {
            transaction.sendNewMessage(message)
        } catch (e: Exception) {
            if (propagateErrors) throw e
            context.showErrorToast(e)
        }
    }

    fun maybeShowErrorToast(resultCode: Int, errorCode: Int) {
        if (resultCode != Activity.RESULT_OK) {
            val msg = if (errorCode != SendStatusReceiver.NO_ERROR_CODE) {
                context.getString(R.string.carrier_send_error)
            } else {
                when (resultCode) {
                    SmsManager.RESULT_ERROR_NO_SERVICE -> context.getString(R.string.error_service_is_unavailable)
                    SmsManager.RESULT_ERROR_RADIO_OFF -> context.getString(R.string.error_radio_turned_off)
                    SmsManager.RESULT_NO_DEFAULT_SMS_APP -> context.getString(R.string.sim_card_not_available)
                    else -> context.getString(R.string.unknown_error_occurred_sending_message, resultCode)
                }
            }
            context.toast(msg = msg, length = Toast.LENGTH_LONG)
        }
    }

    companion object {
        const val ADDRESS_SEPARATOR = "|"
        private const val COLUMN_SIM_ID = "sim_id"
    }
}
