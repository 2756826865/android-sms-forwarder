package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.fossify.messages.services.IncomingSmsService
import org.fossify.messages.services.SmsKeepAliveService

/**
 * The platform gives an SMS receiver only a short execution window.  Do not perform database,
 * contact or network work here: Huawei can freeze the process as soon as onReceive returns.
 * Hand the original PDU intent to a foreground, serial service before returning instead.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "received ${intent.action}; handing off to IncomingSmsService")
        SmsKeepAliveService.ensureStarted(context)
        IncomingSmsService.enqueue(context, intent)
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}

