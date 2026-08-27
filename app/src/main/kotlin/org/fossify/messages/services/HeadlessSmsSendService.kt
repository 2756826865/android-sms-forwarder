package org.fossify.messages.services

import android.app.Service
import android.content.Intent
import android.net.Uri
import com.klinker.android.send_message.Settings
import org.fossify.messages.messaging.sendMessageCompat

import org.fossify.messages.messaging.SimResolutionRequest
import org.fossify.messages.messaging.SubscriptionResolver
import org.fossify.messages.models.SmsSendTriggerType

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent == null) {
                return START_NOT_STICKY
            }

            val dataString = intent.dataString
            val rawNumber = when {
                dataString != null -> Uri.decode(
                    dataString
                        .removePrefix("smsto:")
                        .removePrefix("sms:")
                        .removePrefix("mmsto:")
                        .removePrefix("mms:")
                        .trim()
                )
                else -> intent.getStringExtra("address") ?: intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: ""
            }

            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("android.intent.extra.TEXT")
            if (!text.isNullOrEmpty() && rawNumber.isNotBlank()) {
                val simResult = SubscriptionResolver.resolve(
                    this,
                    SimResolutionRequest(
                        targetAddress = rawNumber.ifBlank { null },
                        allowFallback = true
                    )
                )
                val subId = if (simResult.isSuccessful) simResult.resolvedSubscriptionId else Settings.DEFAULT_SUBSCRIPTION_ID
                val addresses = listOf(rawNumber)
                sendMessageCompat(
                    text = text,
                    addresses = addresses,
                    subId = subId,
                    attachments = emptyList(),
                    triggerType = SmsSendTriggerType.HEADLESS
                )
            }
        } catch (ignored: Exception) {
        }

        return super.onStartCommand(intent, flags, startId)
    }
}
