package org.fossify.messages.receivers

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony.Sms
import android.util.Log
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.helpers.SmsSendRepository
import org.fossify.messages.remote.RemoteControlReceiptForwarder

/** Handles updating databases and states when a sent SMS message is delivered. */
class SmsStatusDeliveredReceiver : SendStatusReceiver() {

    private var status: Int = Sms.Sent.STATUS_NONE

    override fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        val messageUri: Uri? = intent.data
        val smsMessage = runCatching { context.messagingUtils.getSmsMessageFromDeliveryReport(intent) }.getOrNull() ?: return

        try {
            val format = intent.getStringExtra("format")
            status = smsMessage.status
            // Simple matching up CDMA status with GSM status.
            if ("3gpp2" == format) {
                val errorClass = status shr 24 and 0x03
                val statusCode = status shr 16 and 0x3f
                status = when (errorClass) {
                    0 -> {
                        if (statusCode == 0x02 /*STATUS_DELIVERED*/) {
                            Sms.STATUS_COMPLETE
                        } else {
                            Sms.STATUS_PENDING
                        }
                    }

                    2 -> {
                        // TODO: Need to check whether SC still trying to deliver the SMS to destination and will send the report again?
                        Sms.STATUS_PENDING
                    }

                    3 -> {
                        Sms.STATUS_FAILED
                    }

                    else -> {
                        Sms.STATUS_PENDING
                    }
                }
            }
        } catch (e: NullPointerException) {
            // Sometimes, SmsMessage.mWrappedSmsMessage is null causing NPE when we access
            // the methods on it although the SmsMessage itself is not null.
            return
        }

        updateSmsStatus(context, messageUri)
    }

    private fun updateSmsStatus(context: Context, messageUri: Uri?) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            if (status != Sms.Sent.STATUS_NONE) {
                put(Sms.Sent.STATUS, status)
            }
        }

        if (messageUri != null) {
            resolver.update(messageUri, values, null, null)
        } else {
            // 1B-3C: Prohibit blind "date desc" guesswork update to avoid corrupting unrelated SMS records
            Log.w(TAG, "updateSmsStatusAndDateSent skipped: messageUri is null, avoiding blind date desc update")
        }
    }

    override fun updateAppDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        if (status == Sms.STATUS_NONE) return // Missing/unreadable PDU is not a delivery failure.
        val terminal = status == Sms.STATUS_COMPLETE || status >= Sms.STATUS_FAILED
        val messageUri: Uri? = intent.data
        val sendOperationId = intent.getStringExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID)
        val partIndex = if (intent.hasExtra(SendStatusReceiver.EXTRA_PART_INDEX)) {
            intent.getIntExtra(SendStatusReceiver.EXTRA_PART_INDEX, 0)
        } else {
            null
        }

        // 1B-3C: Record delivered status to shadow repository (Fail-open, non-blocking)
        if (terminal && !sendOperationId.isNullOrBlank()) {
            SmsSendRepository.recordDeliveredResult(
                context = context,
                operationId = sendOperationId,
                partIndex = partIndex,
                resultCode = status
            )
        }

        Log.i(TAG, "updateAppDatabase: uri=$messageUri, status=$status, opId=$sendOperationId, part=$partIndex")

        if (messageUri != null) {
            val messageId = messageUri.lastPathSegment?.toLongOrNull() ?: 0L
            run {
                if (status != Sms.Sent.STATUS_NONE) {
                    context.messagesDB.updateStatus(messageId, status)
                }
                if (terminal) RemoteControlReceiptForwarder.onDelivered(
                    context = context,
                    messageId = messageId,
                    delivered = status == Sms.STATUS_COMPLETE,
                )
                refreshMessages()
            }
        }
    }

    private companion object {
        const val TAG = "SmsStatusDelivered"
    }
}

