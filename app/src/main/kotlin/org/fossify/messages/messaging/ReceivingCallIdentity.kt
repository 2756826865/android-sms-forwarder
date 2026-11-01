package org.fossify.messages.messaging

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.CallLog
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager

/** A unique incoming call may supplement identity; an account ID is never parsed as a SIM ID. */
internal object ReceivingCallIdentity {
    private const val RING_LOOKBACK_MS = 3000L
    private const val CALL_WRITE_GRACE_MS = 2000L

    fun resolve(context: Context, number: String, start: Long, end: Long, broadcastSub: Int): Int {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) !=
                    PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return broadcastSub
        return runCatching {
                val account = uniqueIncomingAccount(context, number, start, end)
                val sub = account?.let {
                    context
                        .getSystemService(TelephonyManager::class.java)
                        .createForPhoneAccountHandle(it)
                        ?.subscriptionId
                }
                when {
                    sub == null || SubscriptionResolver.findActiveInfo(context, sub) == null ->
                        broadcastSub
                    broadcastSub >= 0 && broadcastSub != sub -> -1
                    else -> sub
                }
            }
            .getOrDefault(broadcastSub)
    }

    private fun uniqueIncomingAccount(
        context: Context,
        number: String,
        start: Long,
        end: Long,
    ): PhoneAccountHandle? {
        val matches = mutableListOf<PhoneAccountHandle?>()
        context.contentResolver
            .query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
                    CallLog.Calls.PHONE_ACCOUNT_ID,
                ),
                "${CallLog.Calls.DATE} BETWEEN ? AND ? AND ${CallLog.Calls.TYPE} IN (?,?,?)",
                arrayOf(
                    (start - RING_LOOKBACK_MS).toString(),
                    (end + CALL_WRITE_GRACE_MS).toString(),
                    CallLog.Calls.INCOMING_TYPE.toString(),
                    CallLog.Calls.MISSED_TYPE.toString(),
                    CallLog.Calls.REJECTED_TYPE.toString(),
                ),
                null,
            )
            ?.use { matchingAccounts(context, it, number, matches) }
        return matches.singleOrNull()
    }

    private fun matchingAccounts(
        context: Context,
        cursor: Cursor,
        number: String,
        matches: MutableList<PhoneAccountHandle?>,
    ) {
        while (cursor.moveToNext()) {
            if (PhoneNumberUtils.compare(context, cursor.getString(0).orEmpty(), number)) {
                matches.add(account(cursor))
                if (matches.size > 1) break
            }
        }
    }

    private fun account(cursor: Cursor): PhoneAccountHandle? {
        val component = ComponentName.unflattenFromString(cursor.getString(1).orEmpty())
        val id = cursor.getString(2).orEmpty()
        return if (component != null && id.isNotBlank()) PhoneAccountHandle(component, id) else null
    }
}
