package org.fossify.messages.messaging

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.SmsManager
import com.klinker.android.send_message.Settings

@Suppress("DEPRECATION")
fun getSmsManager(context: Context, subId: Int): SmsManager {
    val validSubId = subId != Settings.DEFAULT_SUBSCRIPTION_ID &&
        subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(SmsManager::class.java)
        return if (validSubId) manager.createForSubscriptionId(subId) else manager
    }
    return if (validSubId) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
}
