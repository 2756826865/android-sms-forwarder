package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.fossify.messages.services.IncomingSmsService
import org.fossify.messages.services.SmsKeepAliveService

/**
 * Immediately hands the protected SMS broadcast to a foreground service.
 * Database, contact, notification and forwarding work must not run in the
 * receiver's short execution window, especially while an OEM device is asleep.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "received ${intent.action}; handing off to IncomingSmsService")
        SmsKeepAliveService.ensureStarted(context.applicationContext)
        IncomingSmsService.enqueue(context.applicationContext, intent)
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
