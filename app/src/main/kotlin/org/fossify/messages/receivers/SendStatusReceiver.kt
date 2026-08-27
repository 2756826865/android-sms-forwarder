package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.messaging.HonorSmsCompatibility

abstract class SendStatusReceiver : BroadcastReceiver() {
    // Updates the status of the message in the internal database
    abstract fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int)

    // allows the implementer to update the status of the message in their database
    abstract fun updateAppDatabase(context: Context, intent: Intent, receiverResultCode: Int)

    override fun onReceive(context: Context, intent: Intent) {
        val resultCode = resultCode
        val msgId = intent.data?.lastPathSegment
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        android.util.Log.d("MessagingDebug", "SentIntent extras: msgId=$msgId, threadId=$threadId, address=$address")
        
        HonorSmsCompatibility.complete(context, intent.getStringExtra(EXTRA_SEND_GUARD_KEY))
        ensureBackgroundThread {
            updateAndroidDatabase(context, intent, resultCode)
            updateAppDatabase(context, intent, resultCode)
        }
    }

    companion object {
        const val SMS_SENT_ACTION = "org.fossify.org.fossify.messages.receiver.SMS_SENT"
        const val SMS_DELIVERED_ACTION = "org.fossify.org.fossify.messages.receiver.SMS_DELIVERED"

        // Defined by platform, but no constant provided. See docs for SmsManager.sendTextMessage.
        const val EXTRA_ERROR_CODE = "errorCode"
        const val EXTRA_SUB_ID = "subId"
        const val EXTRA_SEND_GUARD_KEY = "sendGuardKey"
        const val EXTRA_THREAD_ID = "threadId"
        const val EXTRA_ADDRESS = "address"

        // Standardized extras for SmsSendOperation & multipart correlation
        const val EXTRA_SEND_OPERATION_ID = "sendOperationId"
        const val EXTRA_PART_INDEX = "partIndex"
        const val EXTRA_PART_COUNT = "partCount"
        const val EXTRA_IS_LAST_PART = "isLastPart"

        const val NO_ERROR_CODE = -1
    }
}

