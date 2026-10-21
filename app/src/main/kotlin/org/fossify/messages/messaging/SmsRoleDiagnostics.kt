package org.fossify.messages.messaging

import android.content.Context
import android.provider.Telephony
import android.util.Log

/** Observed role changes, never requests a role. No SMS content or phone number. */
internal object SmsRoleDiagnostics {
    @Synchronized fun observe(context: Context, stage: String): Boolean {
        val holder = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        val prefs = context.getSharedPreferences("sms_role_diagnostics", Context.MODE_PRIVATE)
        val changed = prefs.getString("holder", null) != holder
        val now = System.currentTimeMillis()
        val edit = prefs.edit().putString("holder", holder).putLong("observed_at", now)
        if (changed) edit.putLong("change_observed_at", now)
        edit.apply()
        Log.i("SmsRoleDiagnostics", "stage=$stage default=${holder == context.packageName} holder=$holder changed=$changed observedAt=$now")
        return holder == context.packageName
    }
}
