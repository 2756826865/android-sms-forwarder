package org.fossify.messages.services

import android.app.Service
import android.content.Intent
import com.klinker.android.send_message.Settings
import org.fossify.messages.helpers.SmsIntentParser
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

            // 复用 SmsIntentParser，不再自己 `removePrefix("smsto:")/("sms:")/...` 裸剥前缀。
            // 旧实现零结构解析：`smsto://10086/&body=x` 会把整串 `//10086/&body=x` 当成号码，
            // 且完全不解码。现在与 NewConversationActivity 走同一套解析（含收件人解码），
            // 全工程只有一处收件人解析/解码逻辑。
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
                    triggerType = SmsSendTriggerType.HEADLESS
                )
            }
        } catch (ignored: Exception) {
        }

        return super.onStartCommand(intent, flags, startId)
    }
}
