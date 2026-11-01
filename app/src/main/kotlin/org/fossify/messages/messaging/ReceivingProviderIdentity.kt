package org.fossify.messages.messaging

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony

/** An app-authored sub_id is not independent evidence for that same application's old parser. */
internal object ReceivingProviderIdentity {
    fun confirmedSubscription(context: Context, messageId: Long, rawSub: Int): Int {
        if (rawSub < 0) return -1
        val creator = runCatching {
            context.contentResolver
                .query(
                    ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, messageId),
                    arrayOf("creator"),
                    null,
                    null,
                    null,
                )
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }
            .getOrNull()
        if (creator.isNullOrBlank() || creator == context.packageName) return -1
        return if (SubscriptionResolver.findActiveInfo(context, rawSub) != null) rawSub else -1
    }
}
