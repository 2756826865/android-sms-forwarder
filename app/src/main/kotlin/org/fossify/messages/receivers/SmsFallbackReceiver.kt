package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Fallback receiver for standard SMS_RECEIVED broadcasts on OEM ROMs (ColorOS/HyperOS/HarmonyOS) or
 * when the app is running in non-default SMS mode. Deduplication in IncomingSmsService ensures zero
 * duplicate processing.
 */
class SmsFallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }
        Log.i(TAG, "received ${intent.action} fallback broadcast; handing off to IncomingSmsService")
        runCatching {
            val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (!parts.isNullOrEmpty()) {
                val body = buildString { parts.forEach { append(it?.messageBody.orEmpty()) } }
                org.fossify.messages.services.IncomingSmsService.markHandledByBroadcast(
                    body,
                    System.currentTimeMillis(),
                )
            }
        }
        org.fossify.messages.messaging.SmsBroadcastDispatcher.dispatch(
            context,
            Intent(intent),
            goAsync(),
        )
    }

    private companion object {
        const val TAG = "SmsFallbackReceiver"
    }
}
