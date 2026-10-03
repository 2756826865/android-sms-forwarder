package org.fossify.messages.services

import android.app.Service
import android.content.Intent
import android.util.Log
import com.klinker.android.send_message.Settings
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.helpers.SmsIntentParser
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.messages.messaging.SimResolutionRequest
import org.fossify.messages.messaging.SubscriptionResolver
import org.fossify.messages.models.SmsSendTriggerType

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ensureBackgroundThread {
            try {
                // Share recipient parsing with the conversation entry point.
                val (text, recipients) = SmsIntentParser.parseRespondViaMessage(intent)
                val addresses = recipients.split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (text.isNotEmpty() && addresses.isNotEmpty()) {
                    val simResult = SubscriptionResolver.resolve(
                        this,
                        SimResolutionRequest(
                            targetAddress = addresses.first(),
                            allowFallback = true
                        )
                    )
                    val subId = if (simResult.isSuccessful) simResult.resolvedSubscriptionId else Settings.DEFAULT_SUBSCRIPTION_ID
                    sendMessageCompat(
                        text = text,
                        addresses = addresses,
                        subId = subId,
                        attachments = emptyList(),
                        propagateErrors = true,
                        triggerType = SmsSendTriggerType.HEADLESS
                    )
                }
            } catch (error: Exception) {
                Log.e("HeadlessSmsSendService", "Unable to submit respond-via-message SMS: ${error.javaClass.simpleName}")
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }
}
