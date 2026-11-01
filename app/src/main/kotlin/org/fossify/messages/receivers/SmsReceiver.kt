package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Immediately hands the protected SMS broadcast to a foreground service. Database, contact,
 * notification and forwarding work must not run in the receiver's short execution window,
 * especially while an OEM device is asleep.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_DELIVER_ACTION && action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Log.w(TAG, "ignored unexpected SMS action $action")
            return
        }
        Log.i(TAG, "received $action; handing off to IncomingSmsService")
        org.fossify.messages.messaging.SmsBroadcastDispatcher.dispatch(
            context,
            Intent(intent),
            goAsync(),
        )
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
