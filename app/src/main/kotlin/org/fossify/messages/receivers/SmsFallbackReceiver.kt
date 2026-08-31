package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.fossify.messages.services.IncomingSmsService
import org.fossify.messages.services.SmsKeepAliveService

/**
 * Fallback receiver for standard SMS_RECEIVED broadcasts on OEM ROMs (ColorOS/HyperOS/HarmonyOS)
 * or when the app is running in non-default SMS mode.
 * Deduplication in IncomingSmsService ensures zero duplicate processing.
 */
class SmsFallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }
        Log.i(TAG, "received ${intent.action} fallback broadcast; handing off to IncomingSmsService")
        val appContext = context.applicationContext
        SmsKeepAliveService.ensureStarted(appContext)
        IncomingSmsService.enqueue(appContext, intent)
    }

    private companion object {
        const val TAG = "SmsFallbackReceiver"
    }
}
