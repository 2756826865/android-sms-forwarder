package org.fossify.messages.messaging

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneNumberUtils
import org.fossify.commons.helpers.isSPlus
import org.fossify.messages.messaging.SmsException.Companion.EMPTY_DESTINATION_ADDRESS
import org.fossify.messages.messaging.SmsException.Companion.ERROR_SENDING_MESSAGE
import org.fossify.messages.receivers.SendStatusReceiver
import org.fossify.messages.receivers.SmsStatusDeliveredReceiver
import org.fossify.messages.receivers.SmsStatusSentReceiver

/** Class that sends chat message via SMS. */
class SmsSender(val app: Application) {

    // not sure what to do about this yet. this is the default as per android-smsmms
    private val sendMultipartSmsAsSeparateMessages = false

    // This should be called from a RequestWriter queue thread
    fun sendMessage(
        subId: Int, destination: String, body: String, serviceCenter: String?,
        requireDeliveryReport: Boolean, messageUri: Uri, threadId: Long = 0L,
        sendOperationId: String? = null
    ) {
        var dest = destination
        if (body.isEmpty()) {
            throw IllegalArgumentException("SmsSender: empty text message")
        }
        // remove spaces and dashes from destination number
        dest = PhoneNumberUtils.stripSeparators(dest)

        if (dest.isEmpty()) {
            throw SmsException(EMPTY_DESTINATION_ADDRESS)
        }
        // Divide the input message by SMS length limit
        val smsManager = getSmsManager(app, subId)
        val messages = smsManager.divideMessage(body)
        if (messages == null || messages.size < 1) {
            throw SmsException(ERROR_SENDING_MESSAGE)
        }
        // Actually send the sms
        sendInternal(
            subId, dest, messages, serviceCenter, requireDeliveryReport, messageUri, threadId,
            sendOperationId
        )
    }

    // Actually sending the message using SmsManager
    private fun sendInternal(
        subId: Int, dest: String,
        messages: ArrayList<String>, serviceCenter: String?,
        requireDeliveryReport: Boolean, messageUri: Uri, threadId: Long,
        sendOperationId: String?
    ) {
        val smsManager = getSmsManager(app, subId)
        val messageCount = messages.size
        val deliveryIntents = ArrayList<PendingIntent?>(messageCount)
        val sentIntents = ArrayList<PendingIntent>(messageCount)
        val guardKey = HonorSmsCompatibility.claim(app, subId, dest, messages.joinToString(""))
            ?: throw SmsException(
                SmsException.DUPLICATE_SEND_BLOCKED,
                detail = "荣耀兼容保护已阻止重启后的重复发送",
            )
        val effectiveDeliveryReport = requireDeliveryReport && !HonorSmsCompatibility.isAffectedDevice

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (isSPlus()) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }

        try {
            SmsSendCoordinator.observeSubmitting(app, sendOperationId)
            for (i in 0 until messageCount) {
                // Make pending intents different for each message part
                val partId = if (messageCount <= 1) 0 else i + 1
                val isLastPart = (i == messageCount - 1)
                if (effectiveDeliveryReport && isLastPart) {
                    deliveryIntents.add(
                        PendingIntent.getBroadcast(
                            app,
                            partId,
                            getDeliveredStatusIntent(
                                requestUri = messageUri,
                                subId = subId,
                                sendOperationId = sendOperationId,
                                partIndex = i,
                                partCount = messageCount,
                                isLastPart = true
                            ),
                            flags
                        )
                    )
                } else {
                    deliveryIntents.add(null)
                }
                sentIntents.add(
                    PendingIntent.getBroadcast(
                        app,
                        partId,
                        getSendStatusIntent(
                            requestUri = messageUri,
                            subId = subId,
                            guardKey = guardKey,
                            threadId = threadId,
                            address = dest,
                            sendOperationId = sendOperationId,
                            partIndex = i,
                            partCount = messageCount,
                            isLastPart = isLastPart
                        ),
                        flags
                    )
                )
            }
            if (messageCount == 1) {
                smsManager.sendTextMessage(
                    dest,
                    serviceCenter,
                    messages.first(),
                    sentIntents.first(),
                    deliveryIntents.first(),
                )
            } else if (sendMultipartSmsAsSeparateMessages) {
                // If multipart sms is not supported, send them as separate messages
                for (i in 0 until messageCount) {
                    smsManager.sendTextMessage(
                        dest,
                        serviceCenter,
                        messages[i],
                        sentIntents[i],
                        deliveryIntents[i]
                    )
                }
            } else {
                smsManager.sendMultipartTextMessage(
                    dest, serviceCenter, messages, sentIntents, deliveryIntents
                )
            }
            SmsSendCoordinator.observeApiSubmitted(app, sendOperationId, messageCount)
        } catch (e: Exception) {
            HonorSmsCompatibility.complete(app, guardKey)
            SmsSendCoordinator.observeFailure(app, sendOperationId, e.javaClass.name)
            throw SmsException(ERROR_SENDING_MESSAGE, e)
        }
    }

    internal fun getSendStatusIntent(
        requestUri: Uri,
        subId: Int,
        guardKey: String,
        threadId: Long,
        address: String,
        sendOperationId: String? = null,
        partIndex: Int = 0,
        partCount: Int = 1,
        isLastPart: Boolean = true,
    ): Intent {
        val intent = Intent(SendStatusReceiver.SMS_SENT_ACTION, requestUri, app, SmsStatusSentReceiver::class.java)
        intent.putExtra(SendStatusReceiver.EXTRA_SUB_ID, subId)
        intent.putExtra(SendStatusReceiver.EXTRA_THREAD_ID, threadId)
        intent.putExtra(SendStatusReceiver.EXTRA_ADDRESS, address)
        if (guardKey.isNotBlank()) intent.putExtra(SendStatusReceiver.EXTRA_SEND_GUARD_KEY, guardKey)
        if (!sendOperationId.isNullOrBlank()) intent.putExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID, sendOperationId)
        intent.putExtra(SendStatusReceiver.EXTRA_PART_INDEX, partIndex)
        intent.putExtra(SendStatusReceiver.EXTRA_PART_COUNT, partCount)
        intent.putExtra(SendStatusReceiver.EXTRA_IS_LAST_PART, isLastPart)
        return intent
    }

    internal fun getDeliveredStatusIntent(
        requestUri: Uri,
        subId: Int,
        sendOperationId: String? = null,
        partIndex: Int = 0,
        partCount: Int = 1,
        isLastPart: Boolean = true,
    ): Intent {
        val intent = Intent(SendStatusReceiver.SMS_DELIVERED_ACTION, requestUri, app, SmsStatusDeliveredReceiver::class.java)
        intent.putExtra(SendStatusReceiver.EXTRA_SUB_ID, subId)
        if (!sendOperationId.isNullOrBlank()) intent.putExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID, sendOperationId)
        intent.putExtra(SendStatusReceiver.EXTRA_PART_INDEX, partIndex)
        intent.putExtra(SendStatusReceiver.EXTRA_PART_COUNT, partCount)
        intent.putExtra(SendStatusReceiver.EXTRA_IS_LAST_PART, isLastPart)
        return intent
    }

    companion object {
        private var instance: SmsSender? = null
        fun getInstance(app: Application): SmsSender {
            if (instance == null) {
                instance = SmsSender(app)
            }
            return instance!!
        }
    }
}
